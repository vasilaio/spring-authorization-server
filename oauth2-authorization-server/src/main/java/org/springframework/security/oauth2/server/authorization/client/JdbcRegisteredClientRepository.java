/*
 * Copyright 2020-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.server.authorization.client;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.config.ClientSettings;
import org.springframework.security.oauth2.server.authorization.config.TokenSettings;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * JDBC-backed registered client repository
 *
 * @author Rafal Lewczuk
 * @since 0.1.2
 */
public class JdbcRegisteredClientRepository implements RegisteredClientRepository {

	private static final Map<String, AuthorizationGrantType> AUTHORIZATION_GRANT_TYPE_MAP;
	private static final Map<String, ClientAuthenticationMethod> CLIENT_AUTHENTICATION_METHOD_MAP;

	private static final String COLUMN_NAMES = "id, "
			+ "client_id, "
			+ "client_id_issued_at, "
			+ "client_secret, "
			+ "client_secret_expires_at, "
			+ "client_name, "
			+ "client_authentication_methods, "
			+ "authorization_grant_types, "
			+ "redirect_uris, "
			+ "scopes, "
			+ "client_settings,"
			+ "token_settings";

	private static final String TABLE_NAME = "oauth2_registered_client";

	private static final String LOAD_REGISTERED_CLIENT_SQL = "SELECT " + COLUMN_NAMES + " FROM " + TABLE_NAME + " WHERE ";

	private static final String INSERT_REGISTERED_CLIENT_SQL = "INSERT INTO " + TABLE_NAME
			+ "(" + COLUMN_NAMES + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

	private RowMapper<RegisteredClient> registeredClientRowMapper;

	private Function<RegisteredClient, List<SqlParameterValue>> registeredClientParametersMapper;

	private final JdbcOperations jdbcOperations;

	/**
	 * Constructs a {@code JdbcRegisteredClientRepository} using the provided parameters.
	 *
	 * @param jdbcOperations the JDBC operations
	 */
	public JdbcRegisteredClientRepository(JdbcOperations jdbcOperations) {
		Assert.notNull(jdbcOperations, "jdbcOperations cannot be null");
		this.jdbcOperations = jdbcOperations;
		this.registeredClientRowMapper = new DefaultRegisteredClientRowMapper();
		this.registeredClientParametersMapper = new DefaultRegisteredClientParametersMapper();
	}

	/**
	 * Allows changing of {@link RegisteredClient} row mapper implementation
	 *
	 * @param registeredClientRowMapper mapper implementation
	 */
	public final void setRegisteredClientRowMapper(RowMapper<RegisteredClient> registeredClientRowMapper) {
		Assert.notNull(registeredClientRowMapper, "registeredClientRowMapper cannot be null");
		this.registeredClientRowMapper = registeredClientRowMapper;
	}

	/**
	 * Allows changing of SQL parameter mapper for {@link RegisteredClient}
	 *
	 * @param registeredClientParametersMapper mapper implementation
	 */
	public final void setRegisteredClientParametersMapper(Function<RegisteredClient, List<SqlParameterValue>> registeredClientParametersMapper) {
		Assert.notNull(registeredClientParametersMapper, "registeredClientParameterMapper cannot be null");
		this.registeredClientParametersMapper = registeredClientParametersMapper;
	}

	protected final JdbcOperations getJdbcOperations() {
		return this.jdbcOperations;
	}

	protected final RowMapper<RegisteredClient> getRegisteredClientRowMapper() {
		return this.registeredClientRowMapper;
	}

	protected final Function<RegisteredClient, List<SqlParameterValue>> getRegisteredClientParametersMapper() {
		return this.registeredClientParametersMapper;
	}

	@Override
	public void save(RegisteredClient registeredClient) {
		Assert.notNull(registeredClient, "registeredClient cannot be null");
		RegisteredClient foundClient = findBy("id = ? OR client_id = ?",
				registeredClient.getId(), registeredClient.getClientId());

		if (foundClient != null) {
			Assert.isTrue(!foundClient.getId().equals(registeredClient.getId()),
					"Registered client must be unique. Found duplicate identifier: " + registeredClient.getId());
			Assert.isTrue(!foundClient.getClientId().equals(registeredClient.getClientId()),
					"Registered client must be unique. Found duplicate client identifier: " + registeredClient.getClientId());
		}

		List<SqlParameterValue> parameters = this.registeredClientParametersMapper.apply(registeredClient);
		PreparedStatementSetter pss = new ArgumentPreparedStatementSetter(parameters.toArray());
		this.jdbcOperations.update(INSERT_REGISTERED_CLIENT_SQL, pss);
	}

	@Override
	public RegisteredClient findById(String id) {
		Assert.hasText(id, "id cannot be empty");
		return findBy("id = ?", id);
	}

	@Override
	public RegisteredClient findByClientId(String clientId) {
		Assert.hasText(clientId, "clientId cannot be empty");
		return findBy("client_id = ?", clientId);
	}

	private RegisteredClient findBy(String condStr, Object... args) {
		List<RegisteredClient> result = this.jdbcOperations.query(
				LOAD_REGISTERED_CLIENT_SQL + condStr,
				this.registeredClientRowMapper, args);
		return !result.isEmpty() ? result.get(0) : null;
	}

	public static class DefaultRegisteredClientRowMapper implements RowMapper<RegisteredClient> {

		private ObjectMapper objectMapper = new ObjectMapper();

		@Override
		public RegisteredClient mapRow(ResultSet rs, int rowNum) throws SQLException {
			Set<String> clientScopes = parseList(rs.getString("scopes"));
			Set<String> authGrantTypes = parseList(rs.getString("authorization_grant_types"));
			Set<String> clientAuthMethods = parseList(rs.getString("client_authentication_methods"));
			Set<String> redirectUris = parseList(rs.getString("redirect_uris"));
			Timestamp clientIssuedAt = rs.getTimestamp("client_id_issued_at");
			Timestamp clientSecretExpiresAt = rs.getTimestamp("client_secret_expires_at");
			String clientSecret = rs.getString("client_secret");
			RegisteredClient.Builder builder = RegisteredClient
					.withId(rs.getString("id"))
					.clientId(rs.getString("client_id"))
					.clientIdIssuedAt(clientIssuedAt != null ? clientIssuedAt.toInstant() : null)
					.clientSecret(clientSecret)
					.clientSecretExpiresAt(clientSecretExpiresAt != null ? clientSecretExpiresAt.toInstant() : null)
					.clientName(rs.getString("client_name"))
					.authorizationGrantTypes((grantTypes) -> authGrantTypes.forEach(authGrantType ->
							grantTypes.add(AUTHORIZATION_GRANT_TYPE_MAP.get(authGrantType))))
					.clientAuthenticationMethods((authenticationMethods) -> clientAuthMethods.forEach(clientAuthMethod ->
							authenticationMethods.add(CLIENT_AUTHENTICATION_METHOD_MAP.get(clientAuthMethod))))
					.redirectUris((uris) -> uris.addAll(redirectUris))
					.scopes((scopes) -> scopes.addAll(clientScopes));

			RegisteredClient registeredClient = builder.build();

			String tokenSettingsJson = rs.getString("token_settings");
			if (tokenSettingsJson != null) {
				Map<String, Object> settings = parseMap(tokenSettingsJson);
				TokenSettings tokenSettings = registeredClient.getTokenSettings();

				Number accessTokenTTL = (Number) settings.get("access_token_ttl");
				if (accessTokenTTL != null) {
					tokenSettings.accessTokenTimeToLive(Duration.ofMillis(accessTokenTTL.longValue()));
				}

				Number refreshTokenTTL = (Number) settings.get("refresh_token_ttl");
				if (refreshTokenTTL != null) {
					tokenSettings.refreshTokenTimeToLive(Duration.ofMillis(refreshTokenTTL.longValue()));
				}

				Boolean reuseRefreshTokens = (Boolean) settings.get("reuse_refresh_tokens");
				if (reuseRefreshTokens != null) {
					tokenSettings.reuseRefreshTokens(reuseRefreshTokens);
				}
			}

			String clientSettingsJson = rs.getString("client_settings");
			if (clientSettingsJson != null) {
				Map<String, Object> settings = parseMap(clientSettingsJson);
				ClientSettings clientSettings = registeredClient.getClientSettings();

				Boolean requireProofKey = (Boolean) settings.get("require_proof_key");
				if (requireProofKey != null) {
					clientSettings.requireProofKey(requireProofKey);
				}

				Boolean requireUserConsent = (Boolean) settings.get("require_user_consent");
				if (requireUserConsent != null) {
					clientSettings.requireUserConsent(requireUserConsent);
				}
			}

			return registeredClient;
		}

		public final void setObjectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "objectMapper cannot be null");
			this.objectMapper = objectMapper;
		}

		protected final ObjectMapper getObjectMapper() {
			return this.objectMapper;
		}

		private Set<String> parseList(String s) {
			return s != null ? StringUtils.commaDelimitedListToSet(s) : Collections.emptySet();
		}

		private Map<String, Object> parseMap(String data) {
			try {
				return this.objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {});
			} catch (Exception ex) {
				throw new IllegalArgumentException(ex.getMessage(), ex);
			}
		}

	}

	public static class DefaultRegisteredClientParametersMapper implements Function<RegisteredClient, List<SqlParameterValue>> {

		private ObjectMapper objectMapper = new ObjectMapper();

		@Override
		public List<SqlParameterValue> apply(RegisteredClient registeredClient) {
			List<String> clientAuthenticationMethodNames = new ArrayList<>(registeredClient.getClientAuthenticationMethods().size());
			for (ClientAuthenticationMethod clientAuthenticationMethod : registeredClient.getClientAuthenticationMethods()) {
				clientAuthenticationMethodNames.add(clientAuthenticationMethod.getValue());
			}

			List<String> authorizationGrantTypeNames = new ArrayList<>(registeredClient.getAuthorizationGrantTypes().size());
			for (AuthorizationGrantType authorizationGrantType : registeredClient.getAuthorizationGrantTypes()) {
				authorizationGrantTypeNames.add(authorizationGrantType.getValue());
			}

			Instant issuedAt = registeredClient.getClientIdIssuedAt() != null ?
					registeredClient.getClientIdIssuedAt() : Instant.now();

			Timestamp clientSecretExpiresAt = registeredClient.getClientSecretExpiresAt() != null ?
					Timestamp.from(registeredClient.getClientSecretExpiresAt()) : null;

			Map<String, Object> clientSettings = new HashMap<>();
			clientSettings.put("require_proof_key", registeredClient.getClientSettings().requireProofKey());
			clientSettings.put("require_user_consent", registeredClient.getClientSettings().requireUserConsent());
			String clientSettingsJson = writeMap(clientSettings);

			Map<String, Object> tokenSettings = new HashMap<>();
			tokenSettings.put("access_token_ttl", registeredClient.getTokenSettings().accessTokenTimeToLive().toMillis());
			tokenSettings.put("reuse_refresh_tokens", registeredClient.getTokenSettings().reuseRefreshTokens());
			tokenSettings.put("refresh_token_ttl", registeredClient.getTokenSettings().refreshTokenTimeToLive().toMillis());
			String tokenSettingsJson = writeMap(tokenSettings);

			return Arrays.asList(
					new SqlParameterValue(Types.VARCHAR, registeredClient.getId()),
					new SqlParameterValue(Types.VARCHAR, registeredClient.getClientId()),
					new SqlParameterValue(Types.TIMESTAMP, Timestamp.from(issuedAt)),
					new SqlParameterValue(Types.VARCHAR, registeredClient.getClientSecret()),
					new SqlParameterValue(Types.TIMESTAMP, clientSecretExpiresAt),
					new SqlParameterValue(Types.VARCHAR, registeredClient.getClientName()),
					new SqlParameterValue(Types.VARCHAR, StringUtils.collectionToCommaDelimitedString(clientAuthenticationMethodNames)),
					new SqlParameterValue(Types.VARCHAR, StringUtils.collectionToCommaDelimitedString(authorizationGrantTypeNames)),
					new SqlParameterValue(Types.VARCHAR, StringUtils.collectionToCommaDelimitedString(registeredClient.getRedirectUris())),
					new SqlParameterValue(Types.VARCHAR, StringUtils.collectionToCommaDelimitedString(registeredClient.getScopes())),
					new SqlParameterValue(Types.VARCHAR, clientSettingsJson),
					new SqlParameterValue(Types.VARCHAR, tokenSettingsJson));
		}

		public final void setObjectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "objectMapper cannot be null");
			this.objectMapper = objectMapper;
		}

		protected final ObjectMapper getObjectMapper() {
			return this.objectMapper;
		}

		private String writeMap(Map<String, Object> data) {
			try {
				return this.objectMapper.writeValueAsString(data);
			} catch (Exception ex) {
				throw new IllegalArgumentException(ex.getMessage(), ex);
			}
		}

	}

	static {
		Map<String, AuthorizationGrantType> am = new HashMap<>();
		for (AuthorizationGrantType a : Arrays.asList(
				AuthorizationGrantType.AUTHORIZATION_CODE,
				AuthorizationGrantType.REFRESH_TOKEN,
				AuthorizationGrantType.CLIENT_CREDENTIALS,
				AuthorizationGrantType.PASSWORD,
				AuthorizationGrantType.IMPLICIT)) {
			am.put(a.getValue(), a);
		}
		AUTHORIZATION_GRANT_TYPE_MAP = Collections.unmodifiableMap(am);

		Map<String, ClientAuthenticationMethod> cm = new HashMap<>();
		for (ClientAuthenticationMethod c : Arrays.asList(
				ClientAuthenticationMethod.NONE,
				ClientAuthenticationMethod.BASIC,
				ClientAuthenticationMethod.POST)) {
			cm.put(c.getValue(), c);
		}
		CLIENT_AUTHENTICATION_METHOD_MAP = Collections.unmodifiableMap(cm);
	}
}
