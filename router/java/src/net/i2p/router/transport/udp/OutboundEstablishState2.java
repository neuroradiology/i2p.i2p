package net.i2p.router.transport.udp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;

import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.util.Addresses;
import net.i2p.util.Log;

/**
 * Data for a new connection being established, where we initiated the 
 * connection with a remote peer.  In other words, we are Alice and
 * they are Bob.
 *
 * SSU2 only.
 *
 * @since 0.9.54
 */
class OutboundEstablishState2 extends OutboundEstablishState implements SSU2Payload.PayloadCallback {
    private InetSocketAddress _bobSocketAddress;
    private final UDPTransport _transport;
    private final long _sendConnID;
    private final long _rcvConnID;
    private final RouterAddress _routerAddress;
    private long _token;
    private HandshakeState _handshakeState;
    private final byte[] _sendHeaderEncryptKey1;
    private final byte[] _rcvHeaderEncryptKey1;
    private byte[] _sendHeaderEncryptKey2;
    private byte[] _rcvHeaderEncryptKey2;
    private final byte[] _rcvRetryHeaderEncryptKey2;
    private int _mtu;
    private byte[] _sessReqForReTX;
    private byte[] _sessConfForReTX;
    private static final boolean SET_TOKEN = false;

    /**
     *  @param claimedAddress an IP/port based RemoteHostId, or null if unknown
     *  @param remoteHostId non-null, == claimedAddress if direct, or a hash-based one if indirect
     *  @param remotePeer must have supported sig type
     *  @param needIntroduction should we ask Bob to be an introducer for us?
               ignored unless allowExtendedOptions is true
     *  @param introKey Bob's introduction key, as published in the netdb
     *  @param addr non-null
     */
    public OutboundEstablishState2(RouterContext ctx, UDPTransport transport, RemoteHostId claimedAddress,
                                   RemoteHostId remoteHostId, RouterIdentity remotePeer,
                                   boolean needIntroduction,
                                   SessionKey introKey, RouterAddress ra, UDPAddress addr) throws IllegalArgumentException {
        super(ctx, claimedAddress, remoteHostId, remotePeer, needIntroduction, introKey, addr);
        _transport = transport;
        if (claimedAddress != null) {
            try {
                _bobSocketAddress = new InetSocketAddress(InetAddress.getByAddress(_bobIP), _bobPort);
            } catch (UnknownHostException uhe) {
                throw new IllegalArgumentException("bad IP", uhe);
            }
        }
        _mtu = addr.getMTU();
        if (addr.getIntroducerCount() > 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("new outbound establish to " + remotePeer.calculateHash() + ", with address: " + addr);
            _currentState = OutboundState.OB_STATE_PENDING_INTRO;
        } else {
            _currentState = OutboundState.OB_STATE_UNKNOWN;
        }

        _sendConnID = ctx.random().nextLong();
        // rcid == scid is not allowed
        long rcid;
        do {
            rcid = ctx.random().nextLong();
        } while (_sendConnID == rcid);

        _token = _transport.getEstablisher().getOutboundToken(_remotePeer.calculateHash());
        _routerAddress = ra;
        if (_token != 0)
            createNewState(ra);

        _rcvConnID = rcid;
        byte[] ik = introKey.getData();
        _sendHeaderEncryptKey1 = ik;
        _rcvHeaderEncryptKey1 = ik;
        _sendHeaderEncryptKey2 = ik;
        //_rcvHeaderEncryptKey2 will be set after the Session Request message is created
        _rcvRetryHeaderEncryptKey2 = ik;
    }

    private void createNewState(RouterAddress addr) {
        String ss = addr.getOption("s");
        if (ss == null)
            throw new IllegalArgumentException("no SSU2 S");
        byte[] publicKey = Base64.decode(ss);
        if (publicKey == null)
            throw new IllegalArgumentException("bad SSU2 S");
        if (publicKey.length != 32)
            throw new IllegalArgumentException("bad SSU2 S len");
        try {
            _handshakeState = new HandshakeState(HandshakeState.PATTERN_ID_XK_SSU2, HandshakeState.INITIATOR, _transport.getXDHFactory());
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("bad proto", gse);
        }
        _handshakeState.getRemotePublicKey().setPublicKey(publicKey, 0);
        _handshakeState.getLocalKeyPair().setKeys(_transport.getSSU2StaticPrivKey(), 0,
                                                  _transport.getSSU2StaticPubKey(), 0);
    }
    
    public synchronized void restart(long token) {
        _token = token;
        HandshakeState old = _handshakeState;
        byte[] pub = new byte[32];
        old.getRemotePublicKey().getPublicKey(pub, 0);
        createNewState(_routerAddress);
        if (old != null)
            old.destroy();
        //_rcvHeaderEncryptKey2 will be set after the Session Request message is created
        _rcvHeaderEncryptKey2 = null;
    }

    private void processPayload(byte[] payload, int length, boolean isHandshake) throws GeneralSecurityException {
        try {
            int blocks = SSU2Payload.processPayload(_context, this, payload, 0, length, isHandshake);
            System.out.println("Processed " + blocks + " blocks");
        } catch (Exception e) {
            throw new GeneralSecurityException("Session Created payload error", e);
        }
    }

    /////////////////////////////////////////////////////////
    // begin payload callbacks
    /////////////////////////////////////////////////////////

    public void gotDateTime(long time) {
        System.out.println("Got DATE block: " + DataHelper.formatTime(time));
    }

    public void gotOptions(byte[] options, boolean isHandshake) {
        System.out.println("Got OPTIONS block");
    }

    public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
        throw new DataFormatException("RI in Sess Created");
    }

    public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags) {
        throw new IllegalStateException("RI in Sess Created");
    }

    public void gotAddress(byte[] ip, int port) {
        System.out.println("Got ADDRESS block: " + Addresses.toString(ip, port));
    }

    public void gotIntroKey(byte[] key) {
        System.out.println("Got Intro key: " + Base64.encode(key));
    }

    public void gotRelayTagRequest() {
        throw new IllegalStateException("Relay tag req in Sess Created");
    }

    public void gotRelayTag(long tag) {
        System.out.println("Got relay tag " + tag);
    }

    public void gotToken(long token, long expires) {
        _transport.getEstablisher().addOutboundToken(_remotePeer.calculateHash(), token, expires);
    }

    public void gotI2NP(I2NPMessage msg) {
        throw new IllegalStateException("I2NP in Sess Created");
    }

    public void gotFragment(byte[] data, int off, int len, long messageId,int frag, boolean isLast) throws DataFormatException {
        throw new DataFormatException("I2NP in Sess Created");
    }

    public void gotACK(long ackThru, int acks, byte[] ranges) {
        throw new IllegalStateException("ACK in Sess Created");
    }

    public void gotTermination(int reason, long count) {
        throw new IllegalStateException("Termination in Sess Created");
    }

    public void gotUnknown(int type, int len) {
        System.out.println("Got UNKNOWN block, type: " + type + " len: " + len);
    }

    public void gotPadding(int paddingLength, int frameLength) {
    }

    /////////////////////////////////////////////////////////
    // end payload callbacks
    /////////////////////////////////////////////////////////
    
    // SSU 1 unsupported things


    // SSU 2 things

    @Override
    public int getVersion() { return 2; }
    public long getSendConnID() { return _sendConnID; }
    public long getRcvConnID() { return _rcvConnID; }
    public long getToken() { return _token; }
    public long getNextToken() {
        // generate on the fly, this will only be called once
        long token;
        do {
            token = _context.random().nextLong();
        } while (token == 0);
        _transport.getEstablisher().addInboundToken(_remoteHostId, token);
        return token;
    }
    public HandshakeState getHandshakeState() { return _handshakeState; }
    public byte[] getSendHeaderEncryptKey1() { return _sendHeaderEncryptKey1; }
    public byte[] getRcvHeaderEncryptKey1() { return _rcvHeaderEncryptKey1; }
    public byte[] getSendHeaderEncryptKey2() { return _sendHeaderEncryptKey2; }
    public byte[] getRcvHeaderEncryptKey2() { return _rcvHeaderEncryptKey2; }
    public byte[] getRcvRetryHeaderEncryptKey2() { return _rcvRetryHeaderEncryptKey2; }
    public InetSocketAddress getSentAddress() { return _bobSocketAddress; }

    /** what is the largest packet we can send to the peer? */
    public int getMTU() { return _mtu; }

    public synchronized void receiveRetry(UDPPacket packet) throws GeneralSecurityException {
        ////// TODO state check
        createNewState(_routerAddress);
        ////// TODO state change
    }

    public synchronized void receiveSessionCreated(UDPPacket packet) throws GeneralSecurityException {
        ////// todo fix state check
        if (_currentState == OutboundState.OB_STATE_VALIDATION_FAILED) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Session created already failed");
            return;
        }

        DatagramPacket pkt = packet.getPacket();
        SocketAddress from = pkt.getSocketAddress();
        if (!from.equals(_bobSocketAddress))
            throw new GeneralSecurityException("Address mismatch: req: " + _bobSocketAddress + " created: " + from);
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte data[] = pkt.getData();
        _handshakeState.mixHash(data, off, 32);
        if (_log.shouldDebug())
            _log.debug("State after mixHash 2: " + _handshakeState);

        byte[] payload = new byte[len - 80]; // 32 hdr, 32 eph. key, 16 MAC
        try {
            _handshakeState.readMessage(data, off + 32, len - 32, payload, 0);
        } catch (GeneralSecurityException gse) {
            if (_log.shouldDebug())
                _log.debug("Session create error, State at failure: " + _handshakeState + '\n' + net.i2p.util.HexDump.dump(data, off, len), gse);
            throw gse;
        }
        if (_log.shouldDebug())
            _log.debug("State after sess cr: " + _handshakeState);
        processPayload(payload, payload.length, true);
        _sessReqForReTX = null;
        _sendHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessionConfirmed");

        if (_currentState == OutboundState.OB_STATE_UNKNOWN ||
            _currentState == OutboundState.OB_STATE_REQUEST_SENT ||
            _currentState == OutboundState.OB_STATE_INTRODUCED ||
            _currentState == OutboundState.OB_STATE_PENDING_INTRO)
            _currentState = OutboundState.OB_STATE_CREATED_RECEIVED;

        if (_requestSentCount == 1) {
            _rtt = (int) (_context.clock().now() - _requestSentTime);
        }
        packetReceived();
    }

    /**
     * note that we just sent the SessionConfirmed packets
     * and save them for retransmission
     */
    public synchronized void tokenRequestSent(DatagramPacket packet) {
        if (_currentState == OutboundState.OB_STATE_UNKNOWN)
            _currentState = OutboundState.OB_STATE_TOKEN_REQUEST_SENT;
        else if (_currentState == OutboundState.OB_STATE_RETRY_RECEIVED)
            _currentState = OutboundState.OB_STATE_REQUEST_SENT_NEW_TOKEN;
        // don't bother saving for retx, just make a new one every time
    }

    /**
     * note that we just sent the SessionRequest packet
     * and save it for retransmission
     */
    public synchronized void requestSent(DatagramPacket pkt) {
        if (_sessReqForReTX == null) {
            // store pkt for retx
            byte data[] = pkt.getData();
            int off = pkt.getOffset();
            int len = pkt.getLength();
            _sessReqForReTX = new byte[len];
            System.arraycopy(data, off, _sessReqForReTX, 0, len);
        }
        if (_rcvHeaderEncryptKey2 == null)
            _rcvHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");
        requestSent();
    }

    /**
     * note that we just sent the SessionConfirmed packets
     * and save them for retransmission
     */
    public synchronized void confirmedPacketsSent(UDPPacket[] packets) {
        if (_sessConfForReTX == null) {
            // store pkt for retx
            // only one supported right now
            DatagramPacket pkt = packets[0].getPacket();
            byte data[] = pkt.getData();
            int off = pkt.getOffset();
            int len = pkt.getLength();
            _sessConfForReTX = new byte[len];
            System.arraycopy(data, off, _sessConfForReTX, 0, len);
            if (_rcvHeaderEncryptKey2 == null)
                _rcvHeaderEncryptKey2 = SSU2Util.hkdf(_context, _handshakeState.getChainingKey(), "SessCreateHeader");

            // TODO split(), create PeerState2
        }
        confirmedPacketsSent();
    }

    /**
     * @return null if not sent or already got the session created
     */
    public synchronized UDPPacket getRetransmitSessionRequestPacket() {
        if (_sessReqForReTX == null)
            return null;
        UDPPacket packet = UDPPacket.acquire(_context, false);
        DatagramPacket pkt = packet.getPacket();
        byte data[] = pkt.getData();
        int off = pkt.getOffset();
        System.arraycopy(_sessReqForReTX, 0, data, off, _sessReqForReTX.length);
        InetAddress to;
        try {
            to = InetAddress.getByAddress(_bobIP);
        } catch (UnknownHostException uhe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("How did we think this was a valid IP?  " + _remoteHostId);
            packet.release();
            return null;
        }
        pkt.setAddress(to);
        pkt.setPort(_bobPort);
        packet.setMessageType(PacketBuilder2.TYPE_SREQ);
        packet.setPriority(PacketBuilder2.PRIORITY_HIGH);
        requestSent();
        return packet;
    }

    /**
     * @return null we have not sent the session confirmed
     */
    public synchronized PeerState2 getPeerState() {
        // TODO
        // set confirmed pkt data
        return null;
    }

    @Override
    public String toString() {
        return "OES2 " + _remoteHostId + ' ' + _currentState;
    }
}