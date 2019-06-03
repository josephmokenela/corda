package net.corda.bridge.services.receiver

import net.corda.bridge.services.api.*
import net.corda.bridge.services.config.CryptoServiceFactory
import net.corda.bridge.services.receiver.FloatControlTopics.FLOAT_CONTROL_TOPIC
import net.corda.bridge.services.receiver.FloatControlTopics.FLOAT_DATA_TOPIC
import net.corda.bridge.services.util.ServiceStateCombiner
import net.corda.bridge.services.util.ServiceStateHelper
import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.contextLogger
import net.corda.nodeapi.internal.ArtemisMessagingComponent.Companion.P2P_PREFIX
import net.corda.nodeapi.internal.config.CertificateStore
import net.corda.nodeapi.internal.config.MutualSslConfiguration
import net.corda.nodeapi.internal.crypto.KEYSTORE_TYPE
import net.corda.nodeapi.internal.crypto.X509KeyStore
import net.corda.nodeapi.internal.protonwrapper.messages.MessageStatus
import net.corda.nodeapi.internal.protonwrapper.messages.ReceivedMessage
import net.corda.nodeapi.internal.protonwrapper.netty.AMQPConfiguration
import net.corda.nodeapi.internal.protonwrapper.netty.AMQPServer
import net.corda.nodeapi.internal.protonwrapper.netty.ConnectionChange
import net.corda.nodeapi.internal.provider.extractCertificates
import net.corda.nodeapi.internal.protonwrapper.netty.RevocationConfig
import rx.Subscription
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class FloatControlListenerService(val conf: FirewallConfiguration,
                                  val auditService: FirewallAuditService,
                                  private val amqpListener: BridgeAMQPListenerService,
                                  private val stateHelper: ServiceStateHelper = ServiceStateHelper(log)) : FloatControlService, ServiceStateSupport by stateHelper {
    companion object {
        private val log = contextLogger()
    }

    private val lock = ReentrantLock()
    private var statusSubscriber: Subscription? = null
    private var incomingMessageSubscriber: Subscription? = null
    private var connectSubscriber: Subscription? = null
    private var receiveSubscriber: Subscription? = null
    private var amqpControlServer: AMQPServer? = null
    private val floatControlAddress = conf.floatOuterConfig!!.floatAddress
    private val floatClientName = conf.floatOuterConfig!!.expectedCertificateSubject
    private var activeConnectionInfo: ConnectionChange? = null
    private var forwardAddress: NetworkHostAndPort? = null
    private var forwardLegalName: String? = null

    private var p2pSigningService: TLSSigningService? = null
    private val tunnelSigningService: TLSSigningService
    private val tunnelingTruststore: CertificateStore
    private val statusFollower:ServiceStateCombiner

    private var maxMessageSize :Int? = null


    init {
        val sslConfiguration: MutualSslConfiguration = conf.floatOuterConfig?.tunnelSSLConfiguration ?: conf.publicSSLConfiguration
        val cryptoService = CryptoServiceFactory.get(conf.tunnelingCryptoServiceConfig, sslConfiguration.keyStore)
        tunnelSigningService = CryptoServiceSigningService(cryptoService, sslConfiguration.keyStore.get().extractCertificates(), sslConfiguration.trustStore.get(), auditService = auditService)
        tunnelingTruststore = sslConfiguration.trustStore.get()
        statusFollower = ServiceStateCombiner(listOf(auditService, amqpListener, tunnelSigningService))
    }

    override fun start() {
        statusSubscriber = statusFollower.activeChange.subscribe({
            if (it) {
                startControlListener()
            } else {
                stopControlListener()
            }
            stateHelper.active = it
        }, { log.error("Error in state change", it) })
        incomingMessageSubscriber = amqpListener.onReceive.subscribe({
            forwardReceivedMessage(it)
        }, { log.error("Error in state change", it) })
        tunnelSigningService.start()
    }

    private fun startControlListener() {
        lock.withLock {
            val amqpConfig = object : AMQPConfiguration {
                override val userName: String? = null
                override val password: String? = null
                override val keyStore = tunnelSigningService.keyStore()
                override val trustStore = tunnelingTruststore
                // There is no need to have the network maxMessageSize as this value is used to control tunnel messaging. Filtering based on
                // the network maxMessageSize is done elsewhere
                override val maxMessageSize: Int = Int.MAX_VALUE
                override val trace: Boolean = conf.enableAMQPPacketTrace
                override val healthCheckPhrase = conf.healthCheckPhrase
                override val silencedIPs: Set<String> = conf.silencedIPs
                override val revocationConfig: RevocationConfig = conf.revocationConfig
            }
            val controlServer = AMQPServer(floatControlAddress.host,
                    floatControlAddress.port,
                    amqpConfig)
            connectSubscriber = controlServer.onConnection.subscribe({ onConnectToControl(it) }, { log.error("Connection event error", it) })
            receiveSubscriber = controlServer.onReceive.filter { it.topic == FLOAT_CONTROL_TOPIC }
                    .subscribe({ onControlMessage(it) }, { log.error("Receive event error", it) })
            amqpControlServer = controlServer
            controlServer.start()
        }
    }

    override fun stop() {
        lock.withLock {
            stateHelper.active = false
            stopControlListener()
            statusSubscriber?.unsubscribe()
            statusSubscriber = null
            maxMessageSize = null
        }
    }

    private fun stopControlListener() {
        lock.withLock {
            if (amqpListener.running) {
                amqpListener.wipeKeysAndDeactivate()
            }
            connectSubscriber?.unsubscribe()
            connectSubscriber = null
            amqpControlServer?.stop()
            receiveSubscriber?.unsubscribe()
            receiveSubscriber = null
            amqpControlServer = null
            activeConnectionInfo = null
            forwardAddress = null
            forwardLegalName = null
            incomingMessageSubscriber?.unsubscribe()
            incomingMessageSubscriber = null
            p2pSigningService?.stop()
            p2pSigningService = null
            tunnelSigningService.stop()
        }
    }

    private fun onConnectToControl(connectionChange: ConnectionChange) {
        auditService.statusChangeEvent("Connection change on float control port $connectionChange")
        lock.withLock {
            val currentConnection = activeConnectionInfo
            if (currentConnection != null) {
                // If there is a new valid TLS connection kill old connection.
                // Else if this event signals loss of current connection wipe the keys
                if (connectionChange.connected || (currentConnection.remoteAddress == connectionChange.remoteAddress)) {
                    if (amqpListener.running) {
                        amqpListener.wipeKeysAndDeactivate()
                    }
                    amqpControlServer?.dropConnection(currentConnection.remoteAddress)
                    activeConnectionInfo = null
                    forwardAddress = null
                    forwardLegalName = null
                }
            }
            if (connectionChange.connected) {
                if (connectionChange.remoteCert != null) {
                    val certificateSubject = CordaX500Name.parse(connectionChange.remoteCert!!.subjectDN.toString())
                    if (certificateSubject == floatClientName) {
                        activeConnectionInfo = connectionChange
                    } else {
                        amqpControlServer?.dropConnection(connectionChange.remoteAddress)
                    }
                } else {
                    amqpControlServer?.dropConnection(connectionChange.remoteAddress)
                }
            }
        }
    }

    private fun onControlMessage(receivedMessage: ReceivedMessage) {
        when (receivedMessage.topic) {
            FLOAT_CONTROL_TOPIC -> {
                val controlMessage = try {
                    if (CordaX500Name.parse(receivedMessage.sourceLegalName) != floatClientName) {
                        auditService.packetDropEvent(receivedMessage, "Invalid control source legal name!!", RoutingDirection.INBOUND)
                        receivedMessage.complete(true)
                        return
                    }
                    receivedMessage.payload.deserialize<TunnelControlMessage>()
                } catch (ex: Exception) {
                    receivedMessage.complete(true)
                    return
                }
                lock.withLock {
                    when (controlMessage) {
                        is ActivateFloat -> {
                            log.info("Received Tunnel Activate message")
                            val trustStore = CertificateStore.of(loadKeyStore(controlMessage.trustStoreBytes, controlMessage.trustStorePassword), String(controlMessage.trustStorePassword), String(controlMessage.trustStorePassword))
                                    .also { wipeKeys(controlMessage.trustStoreBytes, controlMessage.trustStorePassword) }
                            p2pSigningService = AMQPSigningService(amqpControlServer!!, floatClientName, receivedMessage.sourceLink,
                                    receivedMessage.sourceLegalName, controlMessage.certificates, trustStore, auditService, controlMessage.bridgeCommTimeout)
                            p2pSigningService!!.start()

                            maxMessageSize = controlMessage.maxMessageSize
                            amqpListener.provisionKeysAndActivate(p2pSigningService!!.keyStore(), trustStore, maxMessageSize!!)
                            forwardAddress = receivedMessage.sourceLink
                            forwardLegalName = receivedMessage.sourceLegalName
                        }
                        is DeactivateFloat -> {
                            log.info("Received Tunnel Deactivate message")
                            if (amqpListener.running) {
                                amqpListener.wipeKeysAndDeactivate()
                            }
                            forwardAddress = null
                            forwardLegalName = null
                        }
                    }
                }
            }
            else -> {
                auditService.packetDropEvent(receivedMessage, "Invalid control topic packet received on topic ${receivedMessage.topic}!!", RoutingDirection.INBOUND)
            }
        }
        receivedMessage.complete(true)
    }

    private fun wipeKeys(keyStoreBytes: ByteArray, keyStorePassword: CharArray) {
        // We overwrite the keys we don't need anymore
        Arrays.fill(keyStoreBytes, 0xAA.toByte())
        Arrays.fill(keyStorePassword, 0xAA55.toChar())
    }

    private fun loadKeyStore(keyStoreBytes: ByteArray, keyStorePassword: CharArray): X509KeyStore {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        ByteArrayInputStream(keyStoreBytes).use {
            keyStore.load(it, keyStorePassword)
        }
        return X509KeyStore(keyStore, String(keyStorePassword))
    }

    private fun forwardReceivedMessage(message: ReceivedMessage) {
        val amqpControl = getAmqpControl()
        if (amqpControl == null) {
            message.complete(true) // consume message so it isn't resent forever
            return
        }
        if (message.payload.size > maxMessageSize!!) {
            auditService.packetDropEvent(message, "Message exceeds maxMessageSize network parameter, maxMessageSize: [${message.payload.size}], message size: [$maxMessageSize]. Message is acknowledged and dropped.", RoutingDirection.INBOUND)
            message.complete(true)
            return
        }
        if (!message.topic.startsWith(P2P_PREFIX)) {
            auditService.packetDropEvent(message, "Message topic is not a valid peer namespace ${message.topic}", RoutingDirection.INBOUND)
            message.complete(true) // consume message so it isn't resent forever
            return
        }
        val appProperties = message.applicationProperties.map { Pair(it.key, it.value) }.toList()
        try {
            val wrappedMessage = FloatDataPacket(message.topic,
                    appProperties,
                    message.payload,
                    CordaX500Name.parse(message.sourceLegalName),
                    message.sourceLink,
                    CordaX500Name.parse(message.destinationLegalName),
                    message.destinationLink)
            val amqpForwardMessage = amqpControl.createMessage(wrappedMessage.serialize(context = SerializationDefaults.P2P_CONTEXT).bytes,
                    FLOAT_DATA_TOPIC,
                    forwardLegalName!!,
                    forwardAddress!!,
                    emptyMap())
            amqpForwardMessage.onComplete.then { message.complete(it.get() == MessageStatus.Acknowledged) }
            amqpControl.write(amqpForwardMessage)
            auditService.packetAcceptedEvent(message, RoutingDirection.INBOUND)
        } catch (ex: Exception) {
            log.error("Failed to forward message", ex)
            message.complete(false)
        }
    }

    private fun getAmqpControl(): AMQPServer? {
        return lock.withLock {
            if (amqpControlServer == null ||
                    activeConnectionInfo == null ||
                    forwardLegalName == null ||
                    forwardAddress == null ||
                    !stateHelper.active) {
                null
            } else {
                amqpControlServer
            }
        }
    }
}