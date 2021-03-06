package com.ppublica.shopify.security.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import com.ppublica.shopify.security.authentication.CipherPassword;
import com.ppublica.shopify.security.configuration.SecurityBeansConfig;
import com.ppublica.shopify.security.repository.PersistedStoreAccessToken;
import com.ppublica.shopify.security.repository.PersistedStoreAccessTokenUtility;
import com.ppublica.shopify.security.repository.TokenRepository;

public class TokenServiceTests {
	
	ClientRegistration clientRegistration;
	Collection <? extends GrantedAuthority> authorities;
	
	@BeforeClass
	public static void testSetup() {
		Logger logger = Logger.getLogger(TokenService.class.getName());
		logger.setLevel(Level.FINE);
		Handler handler = new ConsoleHandler();
		handler.setLevel(Level.FINE);
		logger.addHandler(handler);
	}
	
	@Before
	public void setup() {
		clientRegistration = ClientRegistration.withRegistrationId("shopify")
	            .clientId("client-id")
	            .clientSecret("client-secret")
	            .clientAuthenticationMethod(ClientAuthenticationMethod.POST)
	            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
	            .redirectUriTemplate("{baseUrl}/login/app/oauth2/code/{registrationId}")
	            .scope("read_products", "write_products")
	            .authorizationUri("https://{shop}/admin/oauth/authorize")
	            .tokenUri("https://{shop}/admin/oauth/access_token")
	            .clientName("Shopify")
	            .build();
		
		authorities = new ArrayList<SimpleGrantedAuthority>(Arrays.asList(new SimpleGrantedAuthority("read"), new SimpleGrantedAuthority("write")));


	}
	
	@Test
	public void saveNewStoreWhenSavingDelegatesToTokenRepository() {
		// create the TokenService
		TokenRepository repo = mock(TokenRepository.class);
		ClientRegistrationRepository cR = mock(ClientRegistrationRepository.class);
		CipherPassword cp = new CipherPassword("password");
		PersistedStoreAccessTokenUtility utility = mock(PersistedStoreAccessTokenUtility.class);
		
		TokenService tS = new TokenService(repo, cp, cR);
		tS.setPersistedStoreAccessTokenUtility(utility);

		// configure OAuth2AuthorizedClient
		OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
		OAuth2AccessToken accessToken = mock(OAuth2AccessToken.class);

		when(accessToken.getTokenValue()).thenReturn("oauth-token");
		when(client.getAccessToken()).thenReturn(accessToken);

		
		// configure OAuth2AuthenticationToken
		OAuth2AuthenticationToken authentication = mock(OAuth2AuthenticationToken.class);
		
		
		ArgumentCaptor<OAuth2AuthorizedClient> ac = ArgumentCaptor.forClass(OAuth2AuthorizedClient.class);
		ArgumentCaptor<OAuth2AuthenticationToken> pr = ArgumentCaptor.forClass(OAuth2AuthenticationToken.class);
		ArgumentCaptor<EncryptedTokenAndSalt> et = ArgumentCaptor.forClass(EncryptedTokenAndSalt.class);

		
		// invoke method
		tS.saveNewStore(client, authentication);
		
		// assertions
		verify(utility, times(1)).fromAuthenticationObjectsToPersistedStoreAccessToken(ac.capture(), pr.capture(), et.capture());
		verify(repo, times(1)).saveNewStore(ArgumentMatchers.any());
		
		EncryptedTokenAndSalt resultEt = et.getValue();
		Assert.assertFalse(resultEt.getEncryptedToken().isEmpty());
		Assert.assertFalse(resultEt.getSalt().isEmpty());
		
	}
	
	@Test
	public void doesStoreExistWhenYesReturnsTrue() {
		// configure mocks for constructor args
		TokenRepository repo = mock(TokenRepository.class);
		PersistedStoreAccessToken token = mock(PersistedStoreAccessToken.class);
		doReturn(token).when(repo).findTokenForStore("testStore.myshopify.com");

		ClientRegistrationRepository cR = mock(ClientRegistrationRepository.class);
		
		CipherPassword cp = new CipherPassword("password");
		
		// create the TokenService
		TokenService tS = new TokenService(repo, cp, cR);

		// assertions
		Assert.assertTrue(tS.doesStoreExist("testStore.myshopify.com"));
		
	}
	
	@Test
	public void doesStoreExistWhenNoReturnsFalse() {
		// create the TokenService
		TokenRepository repo = mock(TokenRepository.class);
		ClientRegistrationRepository cR = mock(ClientRegistrationRepository.class);
		
		CipherPassword cp = new CipherPassword("password");
		
		TokenService tS = new TokenService(repo, cp, cR);		
		
		// assertions
		Assert.assertFalse(tS.doesStoreExist("testStore.myshopify.com"));
		
	}
	
	
	@Test
	public void getStoreWhenExistsReturnsOAuth2AuthorizedClient() {
		// configure constructor args
		CipherPassword cp = new CipherPassword("password");
		
		ClientRegistrationRepository cR = mock(ClientRegistrationRepository.class);
		doReturn(clientRegistration).when(cR).findByRegistrationId(SecurityBeansConfig.SHOPIFY_REGISTRATION_ID);
				
		
		// create the salt to encode the access token
		String sampleSalt = KeyGenerators.string().generateKey();
		TextEncryptor encryptor = Encryptors.queryableText(cp.getPassword(), sampleSalt);
		String rawTokenValue = "raw-value";
		String encryptedTokenValue = encryptor.encrypt(rawTokenValue);

		// create the PersistedStoreAccessToken returned by the repo
		PersistedStoreAccessToken repoResponse = new PersistedStoreAccessToken();
		repoResponse.setTokenAndSalt(new EncryptedTokenAndSalt(encryptedTokenValue, sampleSalt));
				
		// configure the repo
		TokenRepository repo = mock(TokenRepository.class);
		doReturn(repoResponse).when(repo).findTokenForStore("testStore.myshopify.com");
		

		// create the TokenService
		TokenService tS = new TokenService(repo, cp, cR);
		PersistedStoreAccessTokenUtility utility = mock(PersistedStoreAccessTokenUtility.class);
		tS.setPersistedStoreAccessTokenUtility(utility);
		
		// invoke method
		ArgumentCaptor<PersistedStoreAccessToken> psat = ArgumentCaptor.forClass(PersistedStoreAccessToken.class);
		ArgumentCaptor<DecryptedTokenAndSalt> dts = ArgumentCaptor.forClass(DecryptedTokenAndSalt.class);
		ArgumentCaptor<ClientRegistration> cr = ArgumentCaptor.forClass(ClientRegistration.class);
 
		tS.getStore("testStore.myshopify.com");
		
		// assertions
		
		verify(utility, times(1)).fromPersistedStoreAccessTokenToOAuth2AuthorizedClient(psat.capture(), dts.capture(), cr.capture());
		
		DecryptedTokenAndSalt decryptedToken = dts.getValue();
		ClientRegistration clientReg = cr.getValue();
		
		Assert.assertEquals("raw-value", decryptedToken.getDecryptedToken());
		Assert.assertEquals(sampleSalt, decryptedToken.getSalt());
		Assert.assertEquals("shopify", clientReg.getRegistrationId());

	}
	
	
	@Test
	public void getStoreWhenDoesntExistReturnsNull() {
		// configure constructor args
		CipherPassword cp = new CipherPassword("password");
		ClientRegistrationRepository cR = mock(ClientRegistrationRepository.class);

		TokenRepository repo = mock(TokenRepository.class);
		doReturn(null).when(repo).findTokenForStore("testStore.myshopify.com");
		
		// create the TokenService
		TokenService tS = new TokenService(repo, cp, cR);
		
		Assert.assertNull(tS.getStore("testStore.myshopify.com"));

	}
	
	
	@Test(expected=RuntimeException.class)
	public void getStoreWhenNoShopifyClientRegistrationThrowsException() {
		// configure constructor args
		CipherPassword cp = new CipherPassword("password");
				
		ClientRegistrationRepository cR = mock(ClientRegistrationRepository.class);
		doReturn(null).when(cR).findByRegistrationId(SecurityBeansConfig.SHOPIFY_REGISTRATION_ID);
						
				
		// create the salt to encode the access token
		String sampleSalt = KeyGenerators.string().generateKey();
		TextEncryptor encryptor = Encryptors.queryableText(cp.getPassword(), sampleSalt);
		String rawTokenValue = "raw-value";
		String encryptedTokenValue = encryptor.encrypt(rawTokenValue);

		// create the PersistedStoreAccessToken returned by the repo
		PersistedStoreAccessToken repoResponse = new PersistedStoreAccessToken();
		repoResponse.setTokenAndSalt(new EncryptedTokenAndSalt(encryptedTokenValue, sampleSalt));
						
		// configure the repo
		TokenRepository repo = mock(TokenRepository.class);
		doReturn(repoResponse).when(repo).findTokenForStore("testStore.myshopify.com");
				

		// create the TokenService
		TokenService tS = new TokenService(repo, cp, cR);
				
		// invoke method
		tS.getStore("testStore.myshopify.com");
	
		
	}
	

	@Test
	public void getStoreWhenSaltErrorReturnsNull() {
		// configure constructor args
		CipherPassword cp = new CipherPassword("password");
		
		ClientRegistrationRepository cR = mock(ClientRegistrationRepository.class);
		doReturn(clientRegistration).when(cR).findByRegistrationId(SecurityBeansConfig.SHOPIFY_REGISTRATION_ID);
				
		
		// create the salt to encode the access token
		String sampleSalt = KeyGenerators.string().generateKey();
		TextEncryptor encryptor = Encryptors.queryableText(cp.getPassword(), sampleSalt);
		String rawTokenValue = "raw-value";
		String encryptedTokenValue = encryptor.encrypt(rawTokenValue) + "error";

		// create an OAuth2AccessToken returned by the repo
		PersistedStoreAccessToken repoResponse = new PersistedStoreAccessToken();
		repoResponse.setTokenAndSalt(new EncryptedTokenAndSalt(encryptedTokenValue, sampleSalt));
				
		// configure the repo
		TokenRepository repo = mock(TokenRepository.class);
		doReturn(repoResponse).when(repo).findTokenForStore("testStore.myshopify.com");
		

		// create the TokenService
		TokenService tS = new TokenService(repo, cp, cR);
		
		// invoke method and assertion
		Assert.assertNull(tS.getStore("testStore.myshopify.com"));
		

	}
	
	
	@Test
	public void updateStoreWhenUpdatingDelegatesToTokenRepository() {
		// create the TokenService
		TokenRepository repo = mock(TokenRepository.class);
		ClientRegistrationRepository cR = mock(ClientRegistrationRepository.class);
		CipherPassword cp = new CipherPassword("password");
		PersistedStoreAccessTokenUtility utility = mock(PersistedStoreAccessTokenUtility.class);
				
		TokenService tS = new TokenService(repo, cp, cR);
		tS.setPersistedStoreAccessTokenUtility(utility);

		// configure OAuth2AuthorizedClient
		OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
		OAuth2AccessToken accessToken = mock(OAuth2AccessToken.class);

		when(accessToken.getTokenValue()).thenReturn("oauth-token");
		when(client.getAccessToken()).thenReturn(accessToken);

				
		// configure OAuth2AuthenticationToken
		OAuth2AuthenticationToken authentication = mock(OAuth2AuthenticationToken.class);
				
				
		ArgumentCaptor<OAuth2AuthorizedClient> ac = ArgumentCaptor.forClass(OAuth2AuthorizedClient.class);
		ArgumentCaptor<OAuth2AuthenticationToken> pr = ArgumentCaptor.forClass(OAuth2AuthenticationToken.class);
		ArgumentCaptor<EncryptedTokenAndSalt> et = ArgumentCaptor.forClass(EncryptedTokenAndSalt.class);

				
		// invoke method
		tS.updateStore(client, authentication);
				
		// assertions
		verify(utility, times(1)).fromAuthenticationObjectsToPersistedStoreAccessToken(ac.capture(), pr.capture(), et.capture());
		verify(repo, times(1)).updateStore(ArgumentMatchers.any());
				
		EncryptedTokenAndSalt resultEt = et.getValue();
		Assert.assertFalse(resultEt.getEncryptedToken().isEmpty());
		Assert.assertFalse(resultEt.getSalt().isEmpty());
		
	}
	
	
	@Test
	public void uninstallStoreWhenValidStoreNameCallRepo() {
		
		// create the TokenService
		TokenRepository repo = mock(TokenRepository.class);
		
		ClientRegistrationRepository cR = mock(ClientRegistrationRepository.class);
		
		CipherPassword cp = new CipherPassword("password");
		
		TokenService tS = new TokenService(repo, cp, cR);
		
		// invoke method
		tS.uninstallStore("testStore.myshopify.com");
				
				
		// assertions
		verify(repo, times(1)).uninstallStore("testStore.myshopify.com");
		
		
	}
	
	@Test
	public void uninstallStoreWhenNoStoreNameDontCallRepo() {
		
		// create the TokenService
		TokenRepository repo = mock(TokenRepository.class);
		
		ClientRegistrationRepository cR = mock(ClientRegistrationRepository.class);
		
		CipherPassword cp = new CipherPassword("password");
		
		TokenService tS = new TokenService(repo, cp, cR);
		
		// invoke method
		tS.uninstallStore("");
				
				
		// assertions
		verify(repo, never()).uninstallStore(ArgumentMatchers.any());
		
		
	}


}
