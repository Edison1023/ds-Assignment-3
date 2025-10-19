import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CouncilMember — Single-decree Paxos implementation for the Adelaide Council election.
 *
 * <p>Roles: each process can act as Proposer, Acceptor, Learner.<br>
 * Transport: TCP sockets. One server per member; clients connect per message.<br>
 * Message format: {@code TYPE|FROM|PROPOSAL_NUM|VALUE|ACCEPTED_N|ACCEPTED_V}</p>
 *
 * <p><b>CLI usage</b> (examples):
 * <pre>
 *   java -cp src CouncilMember M1 --profile reliable [--propose M5] [--propose-delay 500]
 *   java -cp src CouncilMember M2 --profile latent
 *   java -cp src CouncilMember M3 --profile failing
 * </pre>
 * </p>
 *
 * <p><b>Config</b>: default {@code ./network.config} provides host:port for M1..M9.</p>
 */
public class CouncilMember {

    // ======= Constants =======
    /** Majority threshold for a 9-member cluster. */
    private static final int MAJORITY = 5;              // 9 members -> majority 5
    /** Default config file path (used by Config.load when main constructs peers map). */
    private static final String CONFIG_PATH = "network.config";
    /** Socket connect timeout in milliseconds. */
    private static final int CONNECT_TIMEOUT_MS = 800;  // socket connect timeout
    /** Request/response read timeout in milliseconds. */
    private static final int RPC_TIMEOUT_MS = 2000;     // request/response wait

    // ======= Identity / Network =======
    /** This member's identifier, e.g., "M1". */
    private final String id; // e.g., "M1"
    /** Mapping from member id to resolved address (includes self). */
    private final Map<String, InetSocketAddress> peers; // memberId -> addr
    /** Network/failure behaviour profile. */
    private final Profile profile; // latency/failure behavior

    // ======= Paxos Acceptor State =======
    /** Highest proposal number promised (Acceptor state). */
    private volatile ProposalNum promisedN = ProposalNum.MIN;      // highest promised
    /** Highest proposal number accepted (Acceptor state). */
    private volatile ProposalNum acceptedN = ProposalNum.MIN;      // highest accepted
    /** Value corresponding to {@link #acceptedN}. */
    private volatile String acceptedV = null;                      // accepted value

    // ======= Learner State =======
    /** Whether a decision has been learned. */
    private volatile boolean decided = false;
    /** The learned/decided value once consensus is reached. */
    private volatile String decidedValue = null;

    // ======= Proposer Counter =======
    /** Per-process monotonically increasing counter used to mint proposal numbers. */
    private final AtomicInteger localCounter = new AtomicInteger(0);

    // ======= Executor =======
    /** I/O pool used for server handling and outgoing RPC calls. */
    private final ExecutorService ioPool = Executors.newCachedThreadPool();

    /**
     * Creates a new council member process.
     *
     * @param id      member id (e.g., "M1")
     * @param peers   mapping "Mi" -&gt; address loaded from config
     * @param profile network/failure profile
     */
    public CouncilMember(String id, Map<String, InetSocketAddress> peers, Profile profile) {
        this.id = id;
        this.peers = peers;
        this.profile = profile;
    }

    // ===================== Main =====================

    /**
     * Program entry. Parses CLI flags, loads config, starts the server
     * and optionally triggers a proposal after a delay.
     *
     * @param args CLI arguments (see class-level usage examples)
     * @throws Exception if the server cannot be started or sleep is interrupted
     */
    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        String id = a.memberId;
        Map<String, InetSocketAddress> peers = Config.load(CONFIG_PATH);
        Profile profile = Profile.from(a.profileName);
        CouncilMember m = new CouncilMember(id, peers, profile);
        m.startServer();
        // Optionally trigger a proposal
        if (a.proposeValue != null) {
            Thread.sleep(a.proposeDelayMs);
            m.propose(a.proposeValue);
        }
    }

    // ===================== Server =====================

    /**
     * Starts a TCP server bound to this member's configured address and port.
     * The accept loop runs on a background task and dispatches each connection
     * to {@link #handleConnection(Socket)}.
     *
     * @throws Exception if the socket fails to bind or accept loop cannot be created
     */
    private void startServer() throws Exception {
        InetSocketAddress me = peers.get(id);
        if (me == null) throw new IllegalStateException("Unknown member in config: " + id);
        ServerSocket server = new ServerSocket();
        server.bind(me);
        log("LISTENING on %s:%d (%s)", me.getHostString(), me.getPort(), profile.displayName());
        ioPool.submit(() -> {
            while (true) {
                try {
                    Socket s = server.accept();
                    ioPool.submit(() -> handleConnection(s));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Handles a single inbound connection: reads one line (one message),
     * optionally simulates drop/latency via {@link Profile}, processes the
     * message with {@link #onMessage(Message)} and writes one-line reply if any.
     *
     * @param s accepted socket
     */
    private void handleConnection(Socket s) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) return;
            // Simulate profile delay/failure before processing
            if (profile.shouldDrop()) {
                log("DROP msg due to failing profile: %s", line);
                return; // no reply
            }
            profile.delay();
            Message req = Message.parse(line);
            Message resp = onMessage(req);
            if (resp != null) {
                bw.write(resp.serialize());
                bw.write("\n");
                bw.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignore) {}
        }
    }

    // ===================== Message Handling (Acceptor/Learner) =====================

    /**
     * Processes a single Paxos message for acceptor/learner roles.
     * <ul>
     *   <li>{@code PREPARE}: promise if n &gt; promisedN</li>
     *   <li>{@code ACCEPT_REQUEST}: accept if n &gt;= promisedN</li>
     *   <li>{@code DECIDE}: learn decided value (idempotent)</li>
     * </ul>
     *
     * @param m message to handle
     * @return response to send back (or {@code null} if no reply required)
     */
    private synchronized Message onMessage(Message m) {
        switch (m.type) {
            case PREPARE:
                if (m.n.compareTo(promisedN) > 0) {
                    promisedN = m.n;
                    log("PROMISE to %s for n=%s (prev accepted n=%s v=%s)", m.from, m.n, acceptedN, acceptedV);
                    return Message.promise(id, m.n, acceptedN, acceptedV);
                } else {
                    log("REJECT PREPARE from %s for n=%s (promised=%s)", m.from, m.n, promisedN);
                    return Message.reject(id, m.n, "promised=" + promisedN);
                }
            case ACCEPT_REQUEST:
                if (m.n.compareTo(promisedN) >= 0) {
                    promisedN = m.n;
                    acceptedN = m.n;
                    acceptedV = m.value;
                    log("ACCEPTED n=%s v=%s from %s", m.n, m.value, m.from);
                    return Message.accepted(id, m.n, m.value);
                } else {
                    log("REJECT ACCEPT_REQUEST n=%s (promised=%s)", m.n, promisedN);
                    return Message.reject(id, m.n, "promised=" + promisedN);
                }
            case DECIDE:
                if (!decided) {
                    decided = true;
                    decidedValue = m.value;
                    log("LEARN CONSENSUS: %s has been elected Council President!", decidedValue);
                    System.out.println("CONSENSUS: " + decidedValue + " has been elected Council President!");
                }
                return Message.ack(id);
            default:
                return Message.error(id, "Unknown type");
        }
    }

    // ===================== Proposer Logic =====================

    /**
     * Runs single-decree Paxos as the proposer for the provided candidate.
     * <ol>
     *   <li>Phase 1/Prepare: broadcast PREPARE, gather PROMISE and any (acceptedN,acceptedV)</li>
     *   <li>Choose value: if any acceptor reports an accepted value with highest acceptedN, use it</li>
     *   <li>Phase 2/Accept: broadcast ACCEPT_REQUEST(value), gather ACCEPTED</li>
     *   <li>Broadcast DECIDE on majority</li>
     * </ol>
     *
     * @param candidate candidate value (e.g., "M5")
     * @throws Exception if the RPC fan-out is interrupted
     */
    public void propose(String candidate) throws Exception {
        ProposalNum n = nextProposalNum();
        log("PROPOSE start n=%s v=%s", n, candidate);

        // Phase 1: PREPARE -> gather promises (with any prior accepted values)
        List<Message> promises = rpcAll(Message.prepare(id, n));
        ProposalNum highestAcceptedN = ProposalNum.MIN;
        String value = candidate;
        int promiseCount = 0;
        for (Message r : promises) {
            if (r.type == Type.PROMISE) {
                promiseCount++;
                if (r.acceptedN != null && r.acceptedV != null && r.acceptedN.compareTo(highestAcceptedN) > 0) {
                    highestAcceptedN = r.acceptedN;
                    value = r.acceptedV; // rule: choose value of highest acceptedN
                }
            }
        }
        if (promiseCount < MAJORITY) {
            log("PHASE1 failed: promises=%d < majority", promiseCount);
            return; // baseline; could retry with higher n
        }

        // Phase 2: ACCEPT_REQUEST with chosen value
        List<Message> accepts = rpcAll(Message.acceptRequest(id, n, value));
        int acceptedCount = 0;
        for (Message r : accepts) if (r.type == Type.ACCEPTED) acceptedCount++;
        if (acceptedCount < MAJORITY) {
            log("PHASE2 failed: accepted=%d < majority", acceptedCount);
            return; // baseline; could backoff & retry
        }

        // DECIDE broadcast
        log("DECIDE majority formed; broadcasting DECIDE(%s)", value);
        rpcAll(Message.decide(id, value));
    }

    /**
     * Generates the next unique, monotonically increasing proposal number owned by this member.
     * The number is encoded as {@code counter.memberIndex}.
     *
     * @return next proposal number
     */
    private ProposalNum nextProposalNum() {
        int c = localCounter.incrementAndGet();
        int memberIdx = Integer.parseInt(id.substring(1));
        return new ProposalNum(c, memberIdx);
    }

    // ===================== RPC =====================

    /**
     * Sends {@code msg} to all peers (excluding self) and collects replies.
     *
     * @param msg message to broadcast
     * @return list of non-null replies received within timeout
     * @throws InterruptedException if the invokeAll call is interrupted
     */
    private List<Message> rpcAll(Message msg) throws InterruptedException {
        List<Callable<Message>> tasks = new ArrayList<>();
        for (Map.Entry<String, InetSocketAddress> e : peers.entrySet()) {
            String peerId = e.getKey();
            if (peerId.equals(id)) continue; // no self-RPC
            tasks.add(() -> rpc(peerId, e.getValue(), msg));
        }
        List<Future<Message>> fut = ioPool.invokeAll(tasks, RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        List<Message> res = new ArrayList<>();
        for (Future<Message> f : fut) {
            try {
                Message r = f.get(1, TimeUnit.MILLISECONDS);
                if (r != null) res.add(r);
            } catch (Exception ignore) {}
        }
        return res;
    }

    /**
     * Sends a single request to a peer and returns its one-line response, if any.
     * Sender-side profile delay/drop is also simulated.
     *
     * @param peerId destination member id
     * @param addr   destination socket address
     * @param msg    outbound message
     * @return parsed reply, or {@code null} on timeout/error/drop
     */
    private Message rpc(String peerId, InetSocketAddress addr, Message msg) {
        try (Socket s = new Socket()) {
            s.connect(addr, CONNECT_TIMEOUT_MS);
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            // Simulate sender-side delay/drop
            if (profile.shouldDrop()) { log("DROP outbound -> %s : %s", peerId, msg); return null; }
            profile.delay();
            bw.write(msg.serialize()); bw.write("\n"); bw.flush();
            s.setSoTimeout(RPC_TIMEOUT_MS);
            String line = br.readLine();
            if (line == null) return null;
            return Message.parse(line);
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== Utils =====================

    /**
     * Prints a timestamped log line with this member's id.
     *
     * @param fmt  printf-style format string
     * @param args format arguments
     */
    private void log(String fmt, Object... args) {
        String ts = String.format("%tT", System.currentTimeMillis());
        System.out.printf("[%s][%s] %s%n", id, ts, String.format(fmt, args));
    }

    // ======= Types =======

    /** Message types supported by this simplified Paxos protocol. */
    enum Type { PREPARE, PROMISE, ACCEPT_REQUEST, ACCEPTED, DECIDE, REJECT, ACK, ERROR }

    /**
     * Immutable, line-serializable Paxos message.
     * Serialized as: {@code TYPE|from|n|value|acceptedN|acceptedV}.
     */
    static class Message {
        final Type type; final String from; final ProposalNum n; final String value; final ProposalNum acceptedN; final String acceptedV;

        /**
         * Constructs a message.
         * @param t   type
         * @param f   sender id
         * @param n   proposal number (nullable)
         * @param v   value (nullable)
         * @param an  previously accepted n (nullable; for PROMISE)
         * @param av  previously accepted v (nullable; for PROMISE)
         */
        Message(Type t, String f, ProposalNum n, String v, ProposalNum an, String av) {
            this.type=t; this.from=f; this.n=n; this.value=v; this.acceptedN=an; this.acceptedV=av;
        }

        /**
         * Parses a serialized line into a {@link Message}.
         * Expected: {@code TYPE|from|n|value|acceptedN|acceptedV}
         * @param line raw line
         * @return parsed message
         * @throws IllegalArgumentException if the type token is invalid
         */
        static Message parse(String line) {
            // type|from|n|value|acceptedN|acceptedV
            String[] p = line.split("\\|", -1);
            Type t = Type.valueOf(p[0]);
            String from = p[1];
            ProposalNum n = p[2].isEmpty()? null : ProposalNum.parse(p[2]);
            String v = p[3].isEmpty()? null : p[3];
            ProposalNum an = p[4].isEmpty()? null : ProposalNum.parse(p[4]);
            String av = p[5].isEmpty()? null : p[5];
            return new Message(t, from, n, v, an, av);
        }

        /**
         * Serializes this message to a single line using '|' delimiters.
         * @return serialized line
         */
        String serialize() {
            return String.join("|",
                    type.name(),
                    ns(from), ns(n), ns(value), ns(acceptedN), ns(acceptedV)
            );
        }

        /** Null-safe toString helper (null -> empty). */
        private static String ns(Object o) { return (o==null? "" : o.toString()); }

        /** Factory: PREPARE. */
        static Message prepare(String from, ProposalNum n){ return new Message(Type.PREPARE, from, n, null, null, null);}
        /** Factory: PROMISE (includes acceptor's last accepted pair). */
        static Message promise(String from, ProposalNum n, ProposalNum an, String av){ return new Message(Type.PROMISE, from, n, null, an, av);}
        /** Factory: ACCEPT_REQUEST. */
        static Message acceptRequest(String from, ProposalNum n, String v){ return new Message(Type.ACCEPT_REQUEST, from, n, v, null, null);}
        /** Factory: ACCEPTED. */
        static Message accepted(String from, ProposalNum n, String v){ return new Message(Type.ACCEPTED, from, n, v, null, null);}
        /** Factory: DECIDE. */
        static Message decide(String from, String v){ return new Message(Type.DECIDE, from, null, v, null, null);}
        /** Factory: REJECT with reason in {@code value} field. */
        static Message reject(String from, ProposalNum n, String reason){ return new Message(Type.REJECT, from, n, reason, null, null);}
        /** Factory: ACK (generic acknowledgement). */
        static Message ack(String from){ return new Message(Type.ACK, from, null, null, null, null);}
        /** Factory: ERROR with message in {@code value} field. */
        static Message error(String from, String msg){ return new Message(Type.ERROR, from, null, msg, null, null);}

        @Override public String toString(){ return serialize(); }
    }

    /**
     * Proposal number with total order. Represented as {@code counter.memberIdx}.
     * Ordering is lexicographic on (counter, memberIdx).
     */
    static class ProposalNum implements Comparable<ProposalNum> {
        /** Sentinel for "no proposal yet". */
        static final ProposalNum MIN = new ProposalNum(-1, -1);
        final int counter; final int memberIdx; // lexicographic

        /**
         * Constructs a proposal number.
         * @param c counter
         * @param m member index extracted from id (e.g., M3 -> 3)
         */
        ProposalNum(int c, int m){ this.counter=c; this.memberIdx=m; }

        /**
         * Parses a string form {@code "<counter>.<memberIdx>"} into a proposal number.
         * @param s string form, e.g., {@code "3.1"}
         * @return parsed {@link ProposalNum}
         * @throws NumberFormatException if tokens are not integers
         */
        static ProposalNum parse(String s){ String[] p=s.split("\\."); return new ProposalNum(Integer.parseInt(p[0]), Integer.parseInt(p[1])); }

        /** Total order: first by counter, then by member index. */
        public int compareTo(ProposalNum o){
            if (counter!=o.counter) return Integer.compare(counter, o.counter);
            return Integer.compare(memberIdx, o.memberIdx);
        }

        @Override public String toString(){ return counter+"."+memberIdx; }
    }

    // ======= Profiles =======

    /**
     * Behaviour profiles to simulate network conditions.
     * RELIABLE: near-zero latency; STANDARD: light jitter; LATENT: heavy jitter; FAILING: may drop.
     */
    enum Profile {
        RELIABLE, STANDARD, LATENT, FAILING;

        /**
         * Parses a profile name (case-insensitive). Unknown values default to STANDARD.
         * @param s profile name
         * @return parsed profile
         */
        static Profile from(String s){
            if (s==null) return STANDARD;
            switch(s.toLowerCase()){
                case "reliable": return RELIABLE;
                case "latent": return LATENT;
                case "failing": return FAILING;
                default: return STANDARD;
            }
        }

        /** Human-friendly display name. */
        String displayName(){
            switch(this){
                case RELIABLE: return "reliable";
                case LATENT: return "latent";
                case FAILING: return "failing";
                default: return "standard";
            }
        }

        /** Adds artificial latency depending on profile. */
        void delay(){
            try{
                switch(this){
                    case RELIABLE: Thread.sleep(0); break;
                    case STANDARD: Thread.sleep(20 + new Random().nextInt(40)); break;
                    case LATENT: Thread.sleep(200 + new Random().nextInt(400)); break;
                    case FAILING: Thread.sleep(10); break;
                }
            }catch(InterruptedException ignore){}
        }

        /**
         * Whether to drop a message under this profile.
         * @return true if the message should be dropped
         */
        boolean shouldDrop(){
            switch(this){
                case FAILING: return new Random().nextDouble() < 0.35; // drop ~35%
                default: return false;
            }
        }
    }

    // ======= Config & Args =======

    /**
     * Config loader. This version expects CSV per line: {@code "M1,127.0.0.1,9001"}.
     * (Keep consistent with the file you provide to the harness.)
     */
    static class Config {
        /**
         * Loads a network config mapping M1..M9 to host/port tuples.
         * Expected format: CSV, e.g., {@code M1,127.0.0.1,9001}
         *
         * @param path config file path
         * @return ordered map (id -> address)
         * @throws IOException when the file cannot be opened/read
         */
        static Map<String, InetSocketAddress> load(String path) throws IOException {
            Map<String, InetSocketAddress> m = new LinkedHashMap<>();
            try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split(",");
                    String id = p[0].trim();
                    String host = p[1].trim();
                    int port = Integer.parseInt(p[2].trim());
                    m.put(id, new InetSocketAddress(host, port));
                }
            }
            return m;
        }
    }

    /**
     * Simple CLI argument holder and parser.
     * Supported flags: {@code --profile}, {@code --propose}, {@code --propose-delay}.
     */
    static class Args {
        final String memberId; final String profileName; final String proposeValue; final int proposeDelayMs;

        /**
         * Creates an Args instance.
         * @param id   member id (e.g., "M1")
         * @param prof profile name (nullable)
         * @param pv   proposed value (nullable)
         * @param d    propose delay in ms (default used by parser = 300)
         */
        Args(String id, String prof, String pv, int d){ memberId=id; profileName=prof; proposeValue=pv; proposeDelayMs=d; }

        /**
         * Parses command-line arguments.
         * Usage:
         * <pre>
         *   java CouncilMember M4 --profile reliable --propose M5 --propose-delay 200
         * </pre>
         *
         * @param args CLI args
         * @return parsed Args
         * @throws NumberFormatException if {@code --propose-delay} is not an integer
         */
        static Args parse(String[] args){
            if (args.length < 2) {
                System.err.println("Usage: java CouncilMember <Mi> --profile <reliable|standard|latent|failing> [--propose Mx] [--propose-delay ms]");
                System.exit(2);
            }
            String id = args[0]; String profile=null; String pv=null; int delay=300; // default 300ms
            for (int i=1; i<args.length; i++){
                switch(args[i]){
                    case "--profile": profile = args[++i]; break;
                    case "--propose": pv = args[++i]; break;
                    case "--propose-delay": delay = Integer.parseInt(args[++i]); break;
                    default: System.err.println("Unknown arg: "+args[i]);
                }
            }
            return new Args(id, profile, pv, delay);
        }
    }
}
