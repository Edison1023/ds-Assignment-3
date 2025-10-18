import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CouncilMember — Single-decree Paxos implementation for the Adelaide Council election.
 *
 * Roles: each process can act as Proposer, Acceptor, Learner.
 * Transport: TCP sockets. One server per member; clients connect per message.
 * Message: simple text format: TYPE|FROM|PROPOSAL_NUM|VALUE|ACCEPTED_N|ACCEPTED_V
 *
 * CLI usage:
 *   java -cp src CouncilMember M1 --profile reliable [--propose M5] [--propose-delay 500]
 *   java -cp src CouncilMember M2 --profile latent
 *   java -cp src CouncilMember M3 --profile failing
 *
 * Config file (default ./network.config) provides host:port for M1..M9
 */
public class CouncilMember {

    // ======= Constants =======
    private static final String CONFIG_PATH = "network.config";
    private static final int CONNECT_TIMEOUT_MS = 800;  // socket connect timeout
    private static final int RPC_TIMEOUT_MS = 2000;     // request/response wait

    // ======= Identity / Network =======
    private final String id; // e.g., "M1"
    private final Map<String, InetSocketAddress> peers; // memberId -> addr
    private final Profile profile; // latency/failure behavior
    private final int majority;

    private ServerSocket serverSocket;

    // ======= Paxos Acceptor State =======
    private volatile ProposalNum promisedN = ProposalNum.MIN;      // highest promised
    private volatile ProposalNum acceptedN = ProposalNum.MIN;      // highest accepted
    private volatile String acceptedV = null;                      // accepted value

    // ======= Learner State =======
    private volatile boolean decided = false;
    private volatile String decidedValue = null;

    // ======= Proposer Counter =======
    private final AtomicInteger localCounter = new AtomicInteger(0);

    // ======= Executor =======
    private final ExecutorService ioPool = Executors.newCachedThreadPool();

    public CouncilMember(String id, Map<String, InetSocketAddress> peers, Profile profile) {
        this.id = id;
        this.peers = peers;
        this.profile = profile;
        this.majority = calculateMajority(peers.size());
    }

    // ===================== Main =====================
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
    private void startServer() throws Exception {
        InetSocketAddress me = peers.get(id);
        if (me == null) throw new IllegalStateException("Unknown member in config: " + id);
        serverSocket = new ServerSocket();
        serverSocket.bind(me);
        log("LISTENING on %s:%d (%s)", me.getHostString(), me.getPort(), profile.displayName());
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopServer));
        ioPool.submit(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket s = serverSocket.accept();
                    ioPool.submit(() -> handleConnection(s));
                } catch (SocketException e) {
                    if (!serverSocket.isClosed()) {
                        log("ERROR accepting connection: %s", e.getMessage());
                    }
                } catch (IOException e) {
                    log("ERROR accepting connection: %s", e.getMessage());
                }
            }
        });
    }

    private void handleConnection(Socket s) {
        SocketAddress remote = null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {
            remote = s.getRemoteSocketAddress();
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
        } catch (Exception e) {
            log("ERROR handling connection from %s: %s", remote != null ? remote : "unknown", e.getMessage());
        } finally {
            try { s.close(); } catch (IOException ignore) {}
        }
    }

    // ===================== Message Handling (Acceptor/Learner) =====================
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
        if (promiseCount < majority) {
            log("PHASE1 failed: promises=%d < majority", promiseCount);
            return; // baseline; could retry with higher n
        }

        // Phase 2: ACCEPT_REQUEST with chosen value
        List<Message> accepts = rpcAll(Message.acceptRequest(id, n, value));
        int acceptedCount = 0;
        for (Message r : accepts) if (r.type == Type.ACCEPTED) acceptedCount++;
        if (acceptedCount < majority) {
            log("PHASE2 failed: accepted=%d < majority", acceptedCount);
            return; // baseline; could backoff & retry
        }

        // DECIDE broadcast
        log("DECIDE majority formed; broadcasting DECIDE(%s)", value);
        rpcAll(Message.decide(id, value));
    }

    private ProposalNum nextProposalNum() {
        int c = localCounter.incrementAndGet();
        int memberIdx = Integer.parseInt(id.substring(1));
        return new ProposalNum(c, memberIdx);
    }

    // ===================== RPC =====================
    private List<Message> rpcAll(Message msg) throws InterruptedException {
        List<Callable<Message>> tasks = new ArrayList<>();
        List<String> peerOrder = new ArrayList<>();
        for (Map.Entry<String, InetSocketAddress> e : peers.entrySet()) {
            String peerId = e.getKey();
            if (peerId.equals(id)) continue; // no self-RPC
            tasks.add(() -> rpc(peerId, e.getValue(), msg));
            peerOrder.add(peerId);
        }
        List<Future<Message>> fut = ioPool.invokeAll(tasks, RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        List<Message> res = new ArrayList<>();
        for (int i = 0; i < fut.size(); i++) {
            Future<Message> f = fut.get(i);
            if (f.isCancelled()) {
                log("RPC to %s cancelled (timeout)", peerOrder.get(i));
                continue;
            }
            try {
                Message r = f.get();
                if (r != null) res.add(r);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log("RPC task failed for %s: %s", peerOrder.get(i), cause.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        return res;
    }

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
            log("RPC to %s failed: %s", peerId, e.getMessage());
            return null;
        }
    }

    // ===================== Utils =====================
    private void log(String fmt, Object... args) {
        String ts = String.format("%tT", System.currentTimeMillis());
        System.out.printf("[%s][%s] %s%n", id, ts, String.format(fmt, args));
    }

    // ======= Types =======
    enum Type { PREPARE, PROMISE, ACCEPT_REQUEST, ACCEPTED, DECIDE, REJECT, ACK, ERROR }

    static class Message {
        final Type type; final String from; final ProposalNum n; final String value; final ProposalNum acceptedN; final String acceptedV;
        Message(Type t, String f, ProposalNum n, String v, ProposalNum an, String av) { this.type=t; this.from=f; this.n=n; this.value=v; this.acceptedN=an; this.acceptedV=av; }
        static Message parse(String line) {
            // type|from|n|value|acceptedN|acceptedV
            String[] p = line.split("\\|", -1);
            if (p.length != 6) {
                throw new IllegalArgumentException("Malformed message, expected 6 fields but got " + p.length);
            }
            Type t = Type.valueOf(p[0]);
            String from = p[1];
            ProposalNum n = p[2].isEmpty()? null : ProposalNum.parse(p[2]);
            String v = p[3].isEmpty()? null : p[3];
            ProposalNum an = p[4].isEmpty()? null : ProposalNum.parse(p[4]);
            String av = p[5].isEmpty()? null : p[5];
            return new Message(t, from, n, v, an, av);
        }
        String serialize() {
            return String.join("|",
                    type.name(),
                    ns(from), ns(n), ns(value), ns(acceptedN), ns(acceptedV)
            );
        }
        private static String ns(Object o) { return (o==null? "" : o.toString()); }

        static Message prepare(String from, ProposalNum n){ return new Message(Type.PREPARE, from, n, null, null, null);}
        static Message promise(String from, ProposalNum n, ProposalNum an, String av){ return new Message(Type.PROMISE, from, n, null, an, av);}
        static Message acceptRequest(String from, ProposalNum n, String v){ return new Message(Type.ACCEPT_REQUEST, from, n, v, null, null);}
        static Message accepted(String from, ProposalNum n, String v){ return new Message(Type.ACCEPTED, from, n, v, null, null);}
        static Message decide(String from, String v){ return new Message(Type.DECIDE, from, null, v, null, null);}
        static Message reject(String from, ProposalNum n, String reason){ return new Message(Type.REJECT, from, n, reason, null, null);}
        static Message ack(String from){ return new Message(Type.ACK, from, null, null, null, null);}
        static Message error(String from, String msg){ return new Message(Type.ERROR, from, null, msg, null, null);}
        public String toString(){ return serialize(); }
    }

    private static int calculateMajority(int members) {
        if (members <= 0) {
            throw new IllegalArgumentException("Member count must be positive");
        }
        return (members / 2) + 1;
    }

    private void stopServer() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log("ERROR closing server socket: %s", e.getMessage());
            }
        }
        if (!ioPool.isShutdown()) {
            ioPool.shutdownNow();
        }
    }

    static class ProposalNum implements Comparable<ProposalNum> {
        static final ProposalNum MIN = new ProposalNum(-1, -1);
        final int counter; final int memberIdx; // lexicographic
        ProposalNum(int c, int m){ this.counter=c; this.memberIdx=m; }
        static ProposalNum parse(String s){ String[] p=s.split("\\."); return new ProposalNum(Integer.parseInt(p[0]), Integer.parseInt(p[1])); }
        public int compareTo(ProposalNum o){
            if (counter!=o.counter) return Integer.compare(counter, o.counter);
            return Integer.compare(memberIdx, o.memberIdx);
        }
        public String toString(){ return counter+"."+memberIdx; }
    }

    // ======= Profiles =======
    enum Profile {
        RELIABLE, STANDARD, LATENT, FAILING;

        static Profile from(String s){
            if (s==null) return STANDARD;
            switch(s.toLowerCase()){
                case "reliable": return RELIABLE;
                case "latent": return LATENT;
                case "failing": return FAILING;
                default: return STANDARD;
            }
        }

        String displayName(){
            switch(this){
                case RELIABLE: return "reliable";
                case LATENT: return "latent";
                case FAILING: return "failing";
                default: return "standard";
            }
        }

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

        boolean shouldDrop(){
            switch(this){
                case FAILING: return new Random().nextDouble() < 0.35; // drop ~35%
                default: return false;
            }
        }
    }

    // ======= Config & Args =======
    static class Config {
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

    static class Args {
        final String memberId; final String profileName; final String proposeValue; final int proposeDelayMs;
        Args(String id, String prof, String pv, int d){ memberId=id; profileName=prof; proposeValue=pv; proposeDelayMs=d; }
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
