/*
 * Copyright (c) 2020-2026, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.custom.user.store;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.user.api.RealmConfiguration;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.claim.ClaimManager;
import org.wso2.carbon.user.core.common.AuthenticationResult;
import org.wso2.carbon.user.core.common.FailureReason;
import org.wso2.carbon.user.core.common.UniqueIDPaginatedSearchResult;
import org.wso2.carbon.user.core.common.UniqueIDPaginatedUsernameSearchResult;
import org.wso2.carbon.user.core.common.Group;
import org.wso2.carbon.user.core.common.User;
import org.wso2.carbon.user.core.jdbc.UniqueIDJDBCUserStoreManager;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.user.core.model.Condition;
import org.wso2.carbon.user.core.profile.ProfileConfigurationManager;
import org.wso2.carbon.utils.Secret;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * This class implements the Custom User Store Manager.
 */
public class CustomUserStoreManager extends UniqueIDJDBCUserStoreManager {

    private static final Log log = LogFactory.getLog(CustomUserStoreManager.class);

    public CustomUserStoreManager() {

    }

    public CustomUserStoreManager(RealmConfiguration realmConfig, Map<String, Object> properties, ClaimManager
            claimManager, ProfileConfigurationManager profileManager, UserRealm realm, Integer tenantId)
            throws UserStoreException {

        super(realmConfig, properties, claimManager, profileManager, realm, tenantId);
        log.info("CustomUserStoreManager initialized...");
    }

    @Override
    public AuthenticationResult doAuthenticateWithID(String preferredUserNameProperty, String preferredUserNameValue,
            Object credential, String profileName) throws UserStoreException {

        // this user store only supports username-based authentication.
        return doAuthenticateWithUserName(preferredUserNameValue, credential);
    }

    @Override
    public AuthenticationResult doAuthenticateWithUserName(String userName, Object credential)
            throws UserStoreException {

        boolean isAuthenticated = false;
        String userID = null;
        User user;
        // In order to avoid unnecessary db queries.
        if (!isValidUserName(userName)) {
            String reason = "Username validation failed.";
            if (log.isDebugEnabled()) {
                log.debug(reason);
            }
            return getAuthenticationResult(reason);
        }

        if (!isValidCredentials(credential)) {
            String reason = "Password validation failed.";
            if (log.isDebugEnabled()) {
                log.debug(reason);
            }
            return getAuthenticationResult(reason);
        }

        try {
            String candidatePassword = String.copyValueOf(((Secret) credential).getChars());
            String sql = "SELECT id, password FROM users WHERE username = ?";

            try (Connection dbConnection = this.getDBConnection();
                 PreparedStatement prepStmt = dbConnection.prepareStatement(sql)) {

                prepStmt.setString(1, userName);
                try (ResultSet rs = prepStmt.executeQuery()) {
                    if (rs.next()) {
                        userID = rs.getString("id");
                        String storedPassword = rs.getString("password");
                        if (candidatePassword.equals(storedPassword)) {
                            isAuthenticated = true;
                        }
                    }
                }
            }
            if (log.isDebugEnabled()) {
                log.debug(userName + " is authenticated? " + isAuthenticated);
            }
        } catch (SQLException exp) {
            log.error("Error occurred while retrieving user authentication info.", exp);
            throw new UserStoreException("Authentication Failure", exp);
        }
        if (isAuthenticated) {
            user = getUser(userID, userName);
            AuthenticationResult authenticationResult = new AuthenticationResult(
                    AuthenticationResult.AuthenticationStatus.SUCCESS);
            authenticationResult.setAuthenticatedUser(user);
            return authenticationResult;
        } else {
            AuthenticationResult authenticationResult = new AuthenticationResult(
                    AuthenticationResult.AuthenticationStatus.FAIL);
            authenticationResult.setFailureReason(new FailureReason("Invalid credentials."));
            return authenticationResult;
        }
    }
    @Override
    public List<User> doListUsersWithID(String filter, int maxItemLimit)
            throws UserStoreException {

        String sqlFilter = (filter == null || filter.isEmpty()) ? "%" : filter.replace("*", "%");
        String sql = maxItemLimit > 0
                ? "SELECT id, username FROM users WHERE username LIKE ? LIMIT ?"
                : "SELECT id, username FROM users WHERE username LIKE ?";
        List<User> users = new ArrayList<>();

        try (Connection conn = getDBConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sqlFilter);
            if (maxItemLimit > 0) {
                ps.setInt(2, maxItemLimit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    users.add(getUser(rs.getString("id"), rs.getString("username")));
                }
            }
        } catch (SQLException e) {
            throw new UserStoreException("Error listing users", e);
        }
        return users;
    }

    @Override
    public String doGetUserNameFromUserIDWithID(String userID) throws UserStoreException {

        if (userID == null) {
            throw new IllegalArgumentException("userID cannot be null.");
        }

        String sqlStmt = "SELECT username FROM users WHERE id = ?";
        try (Connection dbConnection = getDBConnection();
             PreparedStatement prepStmt = dbConnection.prepareStatement(sqlStmt)) {

            prepStmt.setString(1, userID);
            try (ResultSet rs = prepStmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("username");
                }
            }
        } catch (SQLException e) {
            String msg = "Database error occurred while retrieving userName for userID: " + userID;
            if (log.isDebugEnabled()) {
                log.debug(msg, e);
            }
            throw new UserStoreException(msg, e);
        }
        return null;
    }

    @Override
    protected String doGetUserIDFromUserNameWithID(String userName) throws UserStoreException {

        if (userName == null) {
            throw new IllegalArgumentException("userName cannot be null.");
        }

        String sqlStmt = "SELECT id FROM users WHERE username = ?";
        try (Connection dbConnection = getDBConnection();
             PreparedStatement prepStmt = dbConnection.prepareStatement(sqlStmt)) {

            prepStmt.setString(1, userName);
            try (ResultSet rs = prepStmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("id");
                }
            }
        } catch (SQLException e) {
            String msg = "Database error occurred while retrieving userID for userName: " + userName;
            if (log.isDebugEnabled()) {
                log.debug(msg, e);
            }
            throw new UserStoreException(msg, e);
        }
        return null;
    }

    @Override
    public String[] doGetExternalRoleListOfUserWithID(String userID, String filter)
            throws UserStoreException {

        if (log.isDebugEnabled()) {
            log.debug("Getting roles of user: " + userID + " with filter: " + filter);
        }

        String sql = "SELECT role FROM users WHERE id = ?";
        try (Connection conn = getDBConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userID);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String role = rs.getString("role");
                    return (role != null && !role.isEmpty()) ? new String[]{role} : new String[]{};
                }
            }
        } catch (SQLException e) {
            throw new UserStoreException("Error retrieving user roles", e);
        }
        return new String[]{};
    }

    @Override
    public Map<String, String> getUserPropertyValuesWithID(String userID,
                                                           String[] propertyNames, String profileName) throws UserStoreException {

        Map<String, String> map = new HashMap<>();
        String sqlStmt = "SELECT username, role FROM users WHERE id = ?";

        try (Connection dbConnection = getDBConnection();
             PreparedStatement prepStmt = dbConnection.prepareStatement(sqlStmt)) {

            prepStmt.setString(1, userID);
            try (ResultSet rs = prepStmt.executeQuery()) {
                if (rs.next()) {
                    map.put("username", rs.getString("username"));
                    map.put("role", rs.getString("role"));
                    map.put("scimId", userID);
                }
            }
            return map;
        } catch (SQLException e) {
            String errorMessage = "Error occurred while getting property values for user: " + userID;
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        }
    }

    @Override
    protected UniqueIDPaginatedSearchResult doGetUserListWithID(Condition condition, String profileName,
            int limit, int offset, String sortBy, String sortOrder) throws UserStoreException {

        UniqueIDPaginatedSearchResult result = new UniqueIDPaginatedSearchResult();
        if (limit == 0) {
            return result;
        }

        // IS passes offset as 1-based; convert to 0-based for SQL OFFSET
        int sqlOffset = offset > 0 ? offset - 1 : 0;
        String sql = "SELECT id, username FROM users LIMIT ? OFFSET ?";
        List<User> users = new ArrayList<>();

        try (Connection conn = getDBConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, sqlOffset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    users.add(getUser(rs.getString("id"), rs.getString("username")));
                }
            }
        } catch (SQLException e) {
            throw new UserStoreException("Error occurred while fetching paginated user list.", e);
        }
        result.setUsers(users);
        return result;
    }

    @Override
    protected UniqueIDPaginatedUsernameSearchResult doGetUsernameListWithID(Condition condition, String profileName,
            int limit, int offset, String sortBy, String sortOrder) throws UserStoreException {

        UniqueIDPaginatedUsernameSearchResult result = new UniqueIDPaginatedUsernameSearchResult();
        if (limit == 0) {
            return result;
        }

        int sqlOffset = offset > 0 ? offset - 1 : 0;
        String sql = "SELECT username FROM users LIMIT ? OFFSET ?";
        List<String> usernames = new ArrayList<>();

        try (Connection conn = getDBConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, sqlOffset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    usernames.add(rs.getString("username"));
                }
            }
        } catch (SQLException e) {
            throw new UserStoreException("Error occurred while fetching paginated username list.", e);
        }
        result.setUsers(usernames);
        return result;
    }

    @Override
    public List<String> doGetUserListFromPropertiesWithID(String property, String value, String profileName)
            throws UserStoreException {

        List<String> userIDs = new ArrayList<>();
        // Map the attribute name to the actual column. We support "username", "role", and "scimId"/"id".
        String column;
        if ("username".equalsIgnoreCase(property)) {
            column = "username";
        } else if ("role".equalsIgnoreCase(property)) {
            column = "role";
        } else if ("id".equalsIgnoreCase(property) || "scimId".equalsIgnoreCase(property)) {
            column = "id";
        } else {
            // Unknown property — return empty rather than hitting UM_USER.
            return userIDs;
        }

        // column is resolved from a fixed if/else above — not from user input — so concatenation is safe.
        String sql = "SELECT id FROM users WHERE " + column + " = ?";
        try (Connection conn = getDBConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    userIDs.add(rs.getString("id"));
                }
            }
        } catch (SQLException e) {
            throw new UserStoreException(
                    "Database error occurred while listing users for a property : " + property
                            + " & value : " + value + " & profile name : " + profileName, e);
        }
        return userIDs;
    }

    @Override
    public Group doGetGroupFromGroupName(String groupName, List<String> requiredAttributes)
            throws UserStoreException {

        String sql = "SELECT COUNT(*) FROM users WHERE role = ?";
        try (Connection conn = getDBConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    Group group = new Group();
                    // Use the role name itself as a stable group ID.
                    group.setGroupID(groupName);
                    group.setGroupName(UserCoreUtil.addDomainToName(groupName, getMyDomainName()));
                    group.setUserStoreDomain(getMyDomainName());
                    return group;
                }
            }
        } catch (SQLException e) {
            throw new UserStoreException("Error retrieving group: " + groupName, e);
        }
        return null;
    }

    @Override
    public Group doGetGroupFromGroupId(String groupId, List<String> requiredAttributes)
            throws UserStoreException {

        // We use the role name as the group ID, so delegate to the group-name lookup.
        return doGetGroupFromGroupName(groupId, requiredAttributes);
    }

    @Override
    public List<User> doGetUserListOfRoleWithID(String roleName, String filter) throws UserStoreException {

        String sql = "SELECT id, username FROM users WHERE role = ?";
        List<User> users = new ArrayList<>();
        try (Connection conn = getDBConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    users.add(getUser(rs.getString("id"), rs.getString("username")));
                }
            }
        } catch (SQLException e) {
            throw new UserStoreException("Error retrieving users for role: " + roleName, e);
        }
        return users;
    }

    @Override
    public String[] doGetRoleNames(String filter, int maxItemLimit) throws UserStoreException {

        String sql = "SELECT DISTINCT role FROM users WHERE role IS NOT NULL AND role != ''";
        List<String> roles = new ArrayList<>();

        try (Connection conn = getDBConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                // Prepend domain so IS routes getGroupByGroupName back to this user store.
                roles.add(UserCoreUtil.addDomainToName(rs.getString("role"), getMyDomainName()));
            }
        } catch (SQLException e) {
            throw new UserStoreException("Error retrieving role names", e);
        }
        return roles.toArray(new String[0]);
    }

    @Override
    protected Map<String, Map<String, String>> getUsersPropertyValuesWithID(List<String> users,
            String[] propertyNames, String profileName) throws UserStoreException {

        Map<String, Map<String, String>> result = new HashMap<>();
        if (users == null || users.isEmpty()) {
            return result;
        }

        // Build IN clause: SELECT id, username, role FROM users WHERE id IN (?, ?, ...)
        StringBuilder sql = new StringBuilder("SELECT id, username, role FROM users WHERE id IN (");
        for (int i = 0; i < users.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(")");

        try (Connection conn = getDBConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < users.size(); i++) {
                ps.setString(i + 1, users.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String userID = rs.getString("id");
                    Map<String, String> props = new HashMap<>();
                    props.put("username", rs.getString("username"));
                    props.put("role", rs.getString("role"));
                    props.put("scimId", userID);
                    result.put(userID, props);
                }
            }
        } catch (SQLException e) {
            throw new UserStoreException("Error retrieving property values for users", e);
        }

        return result;
    }

    @Override
    public long doCountUsersWithClaims(String claimUri, String value) throws UserStoreException {

        String sql = "SELECT COUNT(id) FROM users";
        try (Connection conn = getDBConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new UserStoreException("Error counting users", e);
        }
        return 0;
    }

    private AuthenticationResult getAuthenticationResult(String reason) {

        AuthenticationResult authenticationResult = new AuthenticationResult(
                AuthenticationResult.AuthenticationStatus.FAIL);
        authenticationResult.setFailureReason(new FailureReason(reason));
        return authenticationResult;
    }
}
