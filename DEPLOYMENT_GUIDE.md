# Custom JDBC User Store Manager — Deployment & Configuration Guide

## Overview

This guide describes how to deploy and configure the Custom JDBC User Store Manager for WSO2 Identity Server. The implementation provided is a **sample** built against a specific database schema. You will likely need to adapt the SQL queries and claim mappings to match your actual schema before deploying to production.

**Tested against:** WSO2 Identity Server 7.1.0 (Update 34), MySQL 8.x

**What this user store does:**
- Authenticates users against a custom JDBC database (read-only — no user creation, updates, or deletion through IS)
- Lists users and groups in the IS admin console and SCIM2 API
- Exposes user attributes (`username`, `role`) for use in SAML assertions and OIDC token claims

---

## Prerequisites

- WSO2 Identity Server 7.1.0 or later
- Java 11
- Maven 3.6+
- A JDBC-compatible database accessible from the IS server
- The JDBC driver JAR for your database (e.g., `mysql-connector-java-8.x.x.jar`)

---

## Step 1 — Adapt the Code to Your Schema

> **This step is mandatory.** The sample is built against the schema below. If your schema differs, update the SQL queries in `CustomUserStoreManager.java` before building.

**Sample schema the code was written for:**

```sql
CREATE TABLE users (
    id       INT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role     VARCHAR(255) NOT NULL
);
```

**Things you must review and adjust:**

| What to check | Location in code | Notes |
|---|---|---|
| Table name | All SQL strings | Replace `users` with your actual table name |
| Username column | `WHERE username = ?` | Replace with your username column name |
| Password column | `SELECT id, password FROM users` | Replace with your password column name |
| Unique user ID column | `SELECT id FROM users` | Must be a stable, unique identifier per user (e.g., UUID or auto-increment PK) |
| Role/group column | `SELECT role FROM users` | Replace with your role/group column, or remove group-related overrides if not applicable |
| Password comparison | `candidatePassword.equals(storedPassword)` | **Critical** — see password hashing note below |

### Password Hashing

The sample compares passwords using plain-text equality. **This is only appropriate if your database stores passwords in plain text.** If your passwords are hashed, replace the comparison logic in `doAuthenticateWithUserName`:

```java
// Plain text (current sample — replace if passwords are hashed)
if (candidatePassword.equals(storedPassword)) {
    isAuthenticated = true;
}

// Example: BCrypt
// if (BCrypt.checkpw(candidatePassword, storedPassword)) {
//     isAuthenticated = true;
// }
```

Add the appropriate hashing library as a Maven dependency and include it in the `Private-Package` OSGi instruction in `pom.xml`.

### Attribute Mapping

The sample exposes three attributes from the database: `username`, `role`, and `scimId` (mapped to the `id` column). These are returned in `getUserPropertyValuesWithID` and `getUsersPropertyValuesWithID`.

If you have additional attributes (e.g., `email`, `firstName`, `lastName`), extend the SQL queries in those methods to also return those columns, and add corresponding `map.put("yourAttribute", rs.getString("your_column"))` entries.

---

## Step 2 — Build the Bundle

```bash
mvn clean package
```

The output JAR will be at:
```
target/custom.jdbc.user.store.manager-1.0.0.jar
```

---

## Step 3 — Deploy the JDBC Driver

Copy your database JDBC driver JAR into:
```
<IS_HOME>/repository/components/lib/
```

For MySQL 8.x:
```bash
cp mysql-connector-java-8.x.x.jar <IS_HOME>/repository/components/lib/
```

---

## Step 4 — Deploy the Custom Bundle

Copy the built JAR into the OSGi dropins directory:
```bash
cp target/custom.jdbc.user.store.manager-1.0.0.jar \
   <IS_HOME>/repository/components/dropins/
```

---

## Step 5 — Register the Custom User Store Type

Add the following to `<IS_HOME>/repository/conf/deployment.toml` so IS discovers the custom user store type in the console dropdown:

```toml
[user_store_mgt]
custom_user_stores = ["org.wso2.custom.user.store.CustomUserStoreManager"]
```

---

## Step 6 — Start the Server

Start (or restart) WSO2 Identity Server:

```bash
sh <IS_HOME>/bin/wso2server.sh start
```

---

## Step 7 — Create the Secondary User Store

1. Log in to the IS Console: `https://<host>:9443/console`
2. Navigate to **User Stores → New User Store**
3. Select **User Store Type**: `CustomUserStoreManager`
4. Fill in the connection details:

| Property | Value | Notes |
|---|---|---|
| Name | e.g., `CUSTOMDB` | The domain name — used as prefix (e.g., `CUSTOMDB/john`) |
| JDBC URL | `jdbc:mysql://<host>:<port>/<db>?allowPublicKeyRetrieval=true&useSSL=false` | Adjust for your DB and driver |
| Driver Class | `com.mysql.cj.jdbc.Driver` | Use `com.mysql.jdbc.Driver` for MySQL 5.x |
| Username | DB username | |
| Password | DB password | |

5. Click **Finish**. IS generates a user store configuration file at:
   ```
   <IS_HOME>/repository/deployment/server/userstores/<NAME>.xml
   ```

---

## Step 8 — Enable Required Properties in the User Store XML

Open the generated XML file and ensure the following properties are set.

```xml
<Property name="ReadOnly">true</Property>
<Property name="GroupIDEnabled">true</Property>
<Property name="ReadGroups">true</Property>
```

If `GroupIDEnabled` is not already present, add it manually.

---

## Step 9 — Configure Claim Mappings

The custom user store exposes attributes by name. You need to map IS local claims to the attribute names returned by the user store so they appear in SAML assertions and OIDC tokens.

### Attribute names returned by this user store

| Attribute name | Source column | Description |
|---|---|---|
| `username` | `users.username` | The user's login name |
| `role` | `users.role` | The user's role (also surfaced as a SCIM group) |
| `scimId` | `users.id` | Internal unique identifier used by IS for SCIM |

---

## Step 10 — Verify

### Users listing
Navigate to **Users → List Users** in the IS Console. Select your user store domain from the dropdown. Your users should appear.

### Authentication
Attempt to log in to a registered application using a user from your custom store. Use the fully-qualified username if the application requires it: `CUSTOMDB/username`.


---

## Known Limitations

| Limitation | Details |
|---|---|
| Read-only | User creation, password changes, and role assignment through IS are not supported. All user management must be done directly in the source database. |
| Single role per user | The sample assumes one role per user. If your users have multiple roles, the `doGetExternalRoleListOfUserWithID` method must be updated to handle a one-to-many relationship. |
| Plain-text password comparison | The sample does plain-text equality. Adapt to match whatever hashing algorithm your database uses. |
| No attribute filtering | `getUsersPropertyValuesWithID` always returns `username`, `role`, and `scimId` regardless of which attributes IS requests. This is harmless but slightly inefficient. |
| Group membership is role-based | SCIM groups are derived from the `role` column. There is no separate group/role table. If you need a many-to-many user-group relationship, the group-related overrides need to be rewritten. |
| MySQL-specific SQL | `LIMIT ? OFFSET ?` syntax is used in pagination queries. For other databases (Oracle, MSSQL, PostgreSQL) these may need to be rewritten. |

