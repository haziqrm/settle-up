# SplitSettle

A group-expenses app where the hard part is the **settlement layer**.

Splitwise tracks who owes what, and already offers pay by bank in the UK. SplitSettle works out the fewest payments needed to clear a whole group's debts, lets each person pay their share through their own bank, and keeps an exact record of what happened, including when payments are late, fail, or arrive twice.

## How settling up works

The app **cannot take money from anyone**. In Open Banking, the payer approves each payment at their own bank. So a settlement run is a *plan* that is tracked together, while each payment is started by its own payer.

1. Someone taps **Settle up**. This creates a settlement run with a deadline.
2. The app nets the group's balances and plans the payments (for example: Ben pays Alex £10, Cara pays Alex £10).
3. Each payer sees their own **Pay** button. Receivers see nothing to do.
4. The payer taps Pay. The backend creates a payment with the provider (the fake bank, an aggregator, or the NatWest sandbox) and sends the payer to their bank to approve it.
5. The payer returns to the app. The result may not be final yet.
6. A webhook or polling tells the backend the outcome. Only on `CONFIRMED` does it write the ledger rows, in the same database transaction as the status change.
7. When every payment is finished, or the deadline passes, the run is closed as `COMPLETE` or `PARTIAL`. Unpaid debts roll into the next run.

The receiver must have a linked account to receive money. A payment to someone without one is blocked with a clear message.

### Payment statuses

```
PLANNED                  in the plan, payer has not acted yet
AWAITING_AUTHORISATION   payment created, payer is at their bank approving it
PENDING                  approved, waiting for the bank to confirm it settled
CONFIRMED                money moved, ledger rows written
FAILED                   bank rejected or errored
EXPIRED                  payer never approved in time
CANCELLED                payer or run cancelled it
```

Run statuses: `OPEN`, `COMPLETE`, `PARTIAL` (deadline passed with some unpaid), `CANCELLED`.

## Features

- Accounts: sign-up, login, email verification, password reset
- Friends (request and accept) and groups (invite by link or email, owner and member roles)
- Expenses with equal and custom splits, stored as whole pence
- Append-only double-entry ledger; mistakes are fixed with correcting entries
- Group-wide debt netting
- Safe, idempotent, payer-initiated payments with crash and duplicate-webhook recovery
- Linked bank accounts (references only, never raw credentials)
- Live balance and payment-status updates for everyone in the group

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.x (Spring Security for auth) |
| Web frontend | React + TypeScript |
| Mobile (optional, later) | React Native, reusing the API client and types |
| Database | PostgreSQL, migrations with Flyway |
| Live updates | Server-sent events or WebSockets |
| Fake bank | Python (FastAPI): randomly fails, delays, or repeats messages |
| Real providers | Aggregator (Yapily or TrueLayer sandbox) plus NatWest Bank of APIs sandbox, behind one interface |
| Running it | Docker Compose |
| Testing | JUnit + Testcontainers, k6 for load tests |
| Later extras | Redis, Prometheus + Grafana, OpenTelemetry |

Leave out Kafka, microservices and Kubernetes at the start. Use the `webhook_events` table and a background worker instead.

```
PaymentProvider (interface)
  |-- FakeBankProvider        always on, used for chaos and load tests
  |-- YapilyProvider          aggregator: many banks, one adapter
  |-- NatWestDirectProvider   headline integration
  `-- StripeBacsProvider      optional: slow, days-long settlement
```

Everything runs in sandboxes with test money. Live payments need regulatory authorisation.

## Repo layout

```
splitsettle/
  backend/              Spring Boot app
    src/main/resources/
      application.yml
      db/migration/V1__init.sql
  fake-bank/            Python service
  frontend/             React app (later)
  docs/                 architecture diagram, short "why I chose X" notes
  docker-compose.yml
  README.md
```

## Key design decisions

- **Money is whole pence.** Never floats.
- **The ledger is append-only.** A database trigger rejects updates and deletes. Balances are calculated by summing entries, never stored.
- **Every ledger entry has exactly one source**, an expense or a confirmed payment. All entries for a group should sum to zero; test this.
- **Idempotency everywhere.** Unique payment keys, unique provider references, one webhook row per event, and one ledger row per payment per user. Duplicates are harmless.
- **Ledger rows and the `CONFIRMED` status change commit together** in one transaction.
- **One open run per group**, enforced by a partial unique index.
- **No bank credentials stored.** Only encrypted consent and account references, with `ACTIVE`, `EXPIRED` or `REVOKED` status.
- **No reading of users' bank transactions**, which avoids the privacy concern.
- **Users are never hard-deleted.** Anonymise them so other people's balances still add up.
- **Check group membership on every request**, and write tests for it.
- **A user can't leave a group until their balance is zero.**

## Build steps

1. **Set up:** repo layout, Docker Compose with Postgres, Spring Boot skeleton, `V1__init.sql`. Run it and add a `/health` endpoint.
2. **Ledger and balances:** add an expense, split it, write entries, calculate balances. Unit-test awkward splits (£10 three ways must total exactly £10.00).
3. **Netting function:** a pure function from net balances to the smallest sensible list of payments. Test it heavily.
4. **Fake bank:** FastAPI service to create a payment, simulate the payer's approval, check status, and send webhooks. Make failure, delay and duplicates configurable.
5. **Settlement run:** create a run, plan payments, payer taps Pay, provider call, webhook handling, ledger write on `CONFIRMED`.
6. **Failure tests:** payer abandons, approval expires, bank fails, duplicate and out-of-order webhooks, server restart mid-payment. Check nobody is paid twice and the ledger always sums to zero.
7. **Auth, friends, groups, invites** (V2+ migrations).
8. **React frontend** for the main flow, then live updates.
9. **Aggregator sandbox, then NatWest sandbox,** replacing the fake bank behind the provider interface.
10. **Proof:** k6 load test with a large number of simulated groups, dashboards, architecture diagram, "why I chose X" notes.
11. **Optional:** React Native client, VRP recurring payments.

You can stop after step 6 and still have a strong project.