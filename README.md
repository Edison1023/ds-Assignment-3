# DS Assignment 3 – Council Election using Paxos

## Overview
This project implements a **single-decree Paxos consensus algorithm** to simulate the election of a “Council President” among 9 distributed members (M1–M9).  
Each member can act as a **Proposer**, **Acceptor**, and **Learner**, communicating via **TCP sockets**.

The system demonstrates consensus formation under:
- Ideal network conditions
- Concurrent proposals
- Fault-tolerant scenarios (latency, failure)

---

## 📁 Project Structure

```
ds-Assignment-3/
│
├── src/
│   ├── CouncilMember.java          # Main Paxos implementation (Proposer, Acceptor, Learner)
│   ├── CouncilMember$*.class       # Compiled class files
│   └── network.config              # Node addresses and ports (M1–M9)
│
├── logs/                           # Default log directory
├── logs-s1/ logs-s2/ logs-s3/      # Output for each test scenario
│
├── run_tests.sh                    # Automated test harness (Linux/macOS/MSYS)
├── run_tests.ps1                   # Optional PowerShell runner for Windows
├── .gitignore
└── README.md
```

---

## Manual Compilation (Optional)

If you prefer to compile and run the program manually instead of using the provided script:

### Step 1 — Compile
From the project root directory:
```bash
javac -d out src/CouncilMember.java
```
This will compile the source file into the `out/` directory.

> The `-d out` flag creates a separate folder for compiled `.class` files, keeping the workspace clean.

---

### Step 2 — Run a Single Member
For example, start **Member M1** in a reliable profile with a proposal:
```bash
java -cp out CouncilMember M1 --profile reliable --propose M5 --propose-delay 200
```

You can run multiple members (M1–M9) in separate terminals using different profiles from `network.config`.

Example:
```bash
java -cp out CouncilMember M2 --profile latent
java -cp out CouncilMember M3 --profile failing
```

---

### Step 3 — Observe Output
Each process will print messages such as:
```
[M1][16:45:58] PROPOSE start n=1.1 v=M5
[M2][16:45:58] LEARN CONSENSUS: M5 has been elected Council President!
```

Once a majority accepts the proposal, **all members will output the consensus**.

---

## Running the Tests

### Step 1 – Compile and Run All Scenarios Automatically
```bash
bash run_tests.sh
```

The script automatically:
- Compiles the source
- Cleans old logs and processes
- Runs the 3 required test scenarios

---

## Scenarios and Expected Outcomes

### **Scenario 1: Ideal Network**
- **Setup:** All 9 members use the `reliable` profile.
- **Test:** One member (e.g., M4) proposes M5 for president.
- **Expected:** All members agree — *M5 has been elected Council President!*

---

### **Scenario 2: Concurrent Proposals**
- **Setup:** All 9 members use `reliable` profile.
- **Test:** Two members propose simultaneously (M1→M1, M8→M8).
- **Expected:** The Paxos algorithm resolves conflict; one single consensus is reached (e.g., *M1*).

---

### **Scenario 3: Fault Tolerance**
- **Setup:**
    - M1 → reliable
    - M2 → latent (slow)
    - M3 → failing (drops messages)
    - M4–M9 → standard
- **Test:** M2, M3, M4 trigger proposals with delays.
- **Expected:** Despite failures and latency, the system reaches consensus (e.g., *M5*).

---

## Example Output

```
=== Scenario 1: Ideal Network ===
CONSENSUS: M5 has been elected Council President!

=== Scenario 2: Concurrent Proposals ===
CONSENSUS: M1 has been elected Council President!

=== Scenario 3: Fault Tolerance ===
CONSENSUS: M5 has been elected Council President!
```

Logs for each scenario are stored under:
```
logs-s1/
logs-s2/
logs-s3/
```

---

## Fault Recovery & Automation

`run_tests.sh` automatically:
- Kills leftover Java processes (both `CouncilMember` and `java.exe`)
- Cleans up `.pid` files
- Recreates the log directories
- Launches members with proper profiles and delays
- Detects when consensus is achieved

---

## Code Quality & Documentation

- All methods in `CouncilMember.java` follow **Javadoc-style** documentation.
- Code follows **high cohesion & low coupling** principles.
- Variable and method names are **clear and descriptive**.
- No “magic numbers”; constants are defined at the top of the class.

---

## References
- Lamport, L. (1998). *The Part-Time Parliament.* ACM Transactions on Computer Systems, 16(2), 133–169.
- Paxos Algorithm Overview: [https://lamport.azurewebsites.net/pubs/paxos-simple.pdf](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf)

---

## 🧑‍💻 Author
**Enze Li (a1909057)**  
The University of Adelaide  
Distributed Systems – Assignment 3 (Paxos Consensus)

---
