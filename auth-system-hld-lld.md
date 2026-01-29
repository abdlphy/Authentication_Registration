# Industry-Level Login System (Spring Boot + PostgreSQL + Redis + Kafka)

> **Stack:** Spring Boot (Java) + PostgreSQL + Redis + Kafka  
> **Goal:** Secure, scalable, enterprise-ready authentication system with RBAC, Refresh Tokens, Rate Limiting, Account Locking, and Async Audit Logging.

---

## 1) Functional Requirements

### Core Authentication
- Register (Signup)
- Login
- Refresh Access Token
- Logout (single device)
- Logout all devices
- Forgot Password + Reset Password
- Email verification

### Authorization
- Role Based Access Control (RBAC)
- (Optional) Permission Based Access Control

### Security Requirements
- Password hashing using **BCrypt**
- Rate limiting using **Redis**
- Account lockout after repeated invalid attempts using **Redis**
- Refresh token stored securely in DB as **hashed token**
- Audit logging for login attempts (success/failure)
- HTTPS only in production

---

## 2) Non-Functional Requirements
- High availability and scalability
- Works with multiple app servers behind load balancer
- Low latency for login APIs (Kafka used to async log events)
- Observability: structured logs + audit logs

---

## 3) High-Level Design (HLD)

### Components
1. **Client**
   - Web / Mobile (React, React Native, etc.)

2. **Load Balancer**
   - Routes traffic to multiple instances of Auth service

3. **Auth Service (Spring Boot)**
   - REST APIs for authentication
   - Uses PostgreSQL for persistent data
   - Uses Redis for rate limiting + lockout
   - Produces Kafka events for audit + notifications

4. **Audit Service (Spring Boot Kafka Consumer)**
   - Consumes auth events from Kafka
   - Writes login audit logs into PostgreSQL

5. **Notification Service (Optional)**
   - Consumes events and sends email/SMS (verification, reset)

---

### System Architecture (Text Diagram)

```
Client (Web/RN)
   |
Load Balancer
   |
Auth Service (Spring Boot)
   |---- Redis (RateLimit + Lock)
   |---- PostgreSQL (users, refresh_tokens)
   |
   |---- Kafka Topic: auth.events
                  |
        -------------------------
        |                       |
   Audit Service           Notification Service
   (Consumer)              (Consumer)
        |
   PostgreSQL (login_audit_logs)
```

---

## 4) Database Design (PostgreSQL)

### 4.1 `users`
Stores user identity + login/security status.

```sql
CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,

  email VARCHAR(255) UNIQUE NOT NULL,
  username VARCHAR(100) UNIQUE,
  phone VARCHAR(20) UNIQUE,

  password_hash TEXT NOT NULL,

  is_active BOOLEAN DEFAULT TRUE,
  is_deleted BOOLEAN DEFAULT FALSE,

  email_verified BOOLEAN DEFAULT FALSE,
  phone_verified BOOLEAN DEFAULT FALSE,

  last_login_at TIMESTAMP NULL,

  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
);
```

---

### 4.2 `roles`
```sql
CREATE TABLE roles (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(50) UNIQUE NOT NULL,
  description TEXT,
  created_at TIMESTAMP DEFAULT NOW()
);
```

---

### 4.3 `user_roles` (Many-to-Many)
```sql
CREATE TABLE user_roles (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
  created_at TIMESTAMP DEFAULT NOW(),
  UNIQUE(user_id, role_id)
);
```

---

### 4.4 `permissions` (Optional)
```sql
CREATE TABLE permissions (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) UNIQUE NOT NULL,
  description TEXT,
  created_at TIMESTAMP DEFAULT NOW()
);
```

---

### 4.5 `role_permissions`
```sql
CREATE TABLE role_permissions (
  id BIGSERIAL PRIMARY KEY,
  role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
  permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
  created_at TIMESTAMP DEFAULT NOW(),
  UNIQUE(role_id, permission_id)
);
```

---

### 4.6 `refresh_tokens`
Stores **hashed refresh token** (never store plain refresh token).

```sql
CREATE TABLE refresh_tokens (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  token_hash TEXT NOT NULL,
  is_revoked BOOLEAN DEFAULT FALSE,

  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP DEFAULT NOW(),
  revoked_at TIMESTAMP NULL,

  ip_address VARCHAR(50),
  user_agent TEXT
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
```

---

### 4.7 `login_audit_logs` (written via Kafka consumer)
Use `event_id` for idempotency (Kafka can deliver twice).

```sql
CREATE TABLE login_audit_logs (
  id BIGSERIAL PRIMARY KEY,

  event_id VARCHAR(100) UNIQUE NOT NULL,
  event_type VARCHAR(50) NOT NULL,

  user_id BIGINT NULL,
  email VARCHAR(255),

  ip_address VARCHAR(50),
  user_agent TEXT,

  is_success BOOLEAN NOT NULL,
  failure_reason VARCHAR(100),

  created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_login_audit_logs_user_id ON login_audit_logs(user_id);
```

---

### 4.8 `password_reset_tokens`
```sql
CREATE TABLE password_reset_tokens (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  token_hash TEXT NOT NULL,
  expires_at TIMESTAMP NOT NULL,

  used_at TIMESTAMP NULL,
  created_at TIMESTAMP DEFAULT NOW()
);
```

---

### 4.9 `email_verification_tokens`
```sql
CREATE TABLE email_verification_tokens (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  token_hash TEXT NOT NULL,
  expires_at TIMESTAMP NOT NULL,

  verified_at TIMESTAMP NULL,
  created_at TIMESTAMP DEFAULT NOW()
);
```

---

## 5) Redis Design (Rate Limit + Lock)

Redis stores temporary security controls.

### Key Naming + TTL

| Feature | Key | TTL |
|--------|-----|-----|
| Login RateLimit (IP) | `rl:login:ip:<ip>` | 60 sec |
| Login RateLimit (Email) | `rl:login:email:<email>` | 900 sec |
| Wrong password attempts | `fail:user:<userId>` | 900 sec |
| Account lock | `lock:user:<userId>` | 900 sec |
| Forgot password limit | `rl:forgot:<email>` | 1 hr |

### Redis Operations Used
- `INCR key`
- `EXPIRE key ttl`
- `SET key value EX ttl`
- `GET key`
- `DEL key`

---

## 6) Kafka Design

### Topic
- **`auth.events`**

### Event Types
- `USER_REGISTERED`
- `LOGIN_SUCCESS`
- `LOGIN_FAILED`
- `REFRESH_TOKEN_USED`
- `LOGOUT`
- `PASSWORD_RESET_REQUESTED`

### Standard Event Format
```json
{
  "eventId": "uuid",
  "eventType": "LOGIN_SUCCESS",
  "timestamp": "2026-01-29T10:15:00Z",
  "data": {
    "userId": 10,
    "email": "abc@gmail.com",
    "ip": "1.2.3.4",
    "userAgent": "Chrome"
  }
}
```

### Consumer Groups
- `audit-service-group`
- `notification-service-group`

### DLQ (Recommended)
- `auth.events.dlq`

---

## 7) API Design

Base path:
- `/api/v1/auth`

### Endpoints
- `POST /register`
- `POST /login`
- `POST /refresh`
- `POST /logout`
- `POST /logout-all`
- `POST /forgot-password`
- `POST /reset-password`
- `POST /verify-email`

---

## 8) Low Level Design (LLD)

### 8.1 Auth Service (Producer)

**Package Structure**
```
com.company.auth
  ├── controller
  │     └── AuthController.java
  ├── service
  │     ├── AuthService.java
  │     ├── TokenService.java
  │     ├── RateLimitService.java
  │     └── PasswordService.java
  ├── repository
  │     ├── UserRepository.java
  │     └── RefreshTokenRepository.java
  ├── entity
  │     ├── User.java
  │     ├── RefreshToken.java
  │     ├── Role.java
  │     └── UserRole.java
  ├── security
  │     ├── JwtAuthFilter.java
  │     ├── SecurityConfig.java
  │     └── CustomUserDetailsService.java
  ├── kafka
  │     └── AuthEventProducer.java
  ├── dto
  └── exception
```

**Responsibilities**
- Validate login/register requests
- Enforce Redis-based rate limits
- Verify password using BCrypt
- Issue JWT Access Token + Refresh Token
- Store refresh token hash in DB
- Produce Kafka events for audit/notification

---

### 8.2 Audit Service (Consumer)

**Package Structure**
```
com.company.audit
  ├── consumer
  │     └── AuthEventConsumer.java
  ├── service
  │     └── AuditLogService.java
  ├── repository
  │     └── LoginAuditLogRepository.java
  ├── entity
  │     └── LoginAuditLog.java
```

**Responsibilities**
- Consume Kafka auth events
- Insert login audit logs into PostgreSQL
- Ensure idempotency using `event_id`

---

## 9) Core Flows

### 9.1 Login Flow (Redis + DB + Kafka)
1. `POST /auth/login`
2. Check Redis IP rate limit
3. Check Redis email rate limit
4. Find user in PostgreSQL
5. Check Redis lock key `lock:user:<id>`
6. Verify password hash using BCrypt
7. If invalid:
   - Redis INCR fail count `fail:user:<id>`
   - If fails >= 5 => set lock key
   - Produce Kafka event `LOGIN_FAILED`
8. If valid:
   - Clear Redis fail + lock keys
   - Generate access token (10–15 min)
   - Generate refresh token (7–30 days)
   - Store refresh token hash in DB
   - Produce Kafka event `LOGIN_SUCCESS`
9. Return tokens to client

---

### 9.2 Refresh Token Flow
1. `POST /auth/refresh`
2. Hash refresh token and find in DB
3. Validate not revoked/expired
4. Issue new access token
5. (Recommended) Refresh token rotation:
   - revoke old token
   - create new token row
6. Produce Kafka event `REFRESH_TOKEN_USED`
7. Return new tokens

---

### 9.3 Logout Flow
1. `POST /auth/logout`
2. Hash refresh token
3. Mark refresh token row as revoked
4. Produce Kafka event `LOGOUT`

---

## 10) Security Best Practices
- Do not reveal whether email exists or password is wrong (`"Invalid credentials"`)
- Always hash refresh tokens before storing
- Always enable HTTPS in prod
- Use HttpOnly cookies for refresh token (web apps)
- Use device tracking (optional)
- Add monitoring for suspicious login attempts

---

## 11) Deployment Notes
Recommended deployments (Docker/Kubernetes):
- `auth-service` (2+ instances)
- `audit-service` (1+ instances)
- PostgreSQL
- Redis
- Kafka (KRaft or Zookeeper)

---

## 12) Final Checklist
✅ JWT Access Token + Refresh Token  
✅ PostgreSQL schema with RBAC + refresh token storage  
✅ Redis rate limiting + account lockout  
✅ Kafka event-driven audit logging  
✅ Consumer idempotency using `event_id`  
✅ Microservice-ready architecture  

---
