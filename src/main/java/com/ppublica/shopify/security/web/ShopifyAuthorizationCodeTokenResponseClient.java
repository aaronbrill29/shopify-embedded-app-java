package com.ppublica.shopify.security.web;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.ppublica.shopify.security.converter.ShopifyOAuth2AccessTokenResponseConverter;

/**
 * An implementation of OAuth2AccessTokenResponseClient that decorates DefaultAuthorizationCodeTokenResponseClient.
 * It's invoked by OAuth2LoginAuthenticationProvider to obtain a OAuth2AccessTokenResponse.
 * 
 * <p>When OAuth2LoginAuthenticationProvider asks ShopifyAuthorizationCodeTokenResponseClient for a token response,
 * this class (DefaultAuthorizationCodeTokenResponseClient does the work) delegates to its converters: 
 * OAuth2AccessTokenResponseHttpMessageConverter and FormHttpMessageConverter.</p>
 * 
 * 
 * <p>This class has 3 main functions:</p>
 * <ul>
 * <li>It customizes the composite DefaultAuthorizationCodeTokenResponseClient by setting a custom 
 * Converter&lt;Map&lt;String, String&gt;, OAuth2AccessTokenResponse&gt; on the OAuth2AccessTokenResponseHttpMessageConverter. 
 * The custom converter is ShopifyOAuth2AccessTokenResponseConverter.</li>
 * 
 * 
 * <li>It expects to find an additional parameter in the OAuth2AuthorizationRequest: the shop name.
 * Since in Shopify every store has a unique tokenUri, this class uses the shop name to generate the store-specific
 * tokenUri, which it uses to create a new "store-specific ClientRegistration."</li>
 * 
 * 
 * <li> AFTER OBTAINING THE RESPONSE, it intercepts the default response client's OAuth2AccessTokenResponse, instead returning 
 * a new OAuth2AccessTokenResponse that contains the shop name as an additional parameter, since it'll be needed later 
 * (OAuth2UserService needs it).</li>
 * </ul>
 * 
 * @see DefaultAuthorizationCodeTokenResponseClient
 * @see org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationProvider
 * 
 */
public class ShopifyAuthorizationCodeTokenResponseClient implements OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> {
	private final Log logger = LogFactory.getLog(ShopifyAuthorizationCodeTokenResponseClient.class);

	private DefaultAuthorizationCodeTokenResponseClient oAuth2AccessTokenResponseClient;
	
	
	
	public ShopifyAuthorizationCodeTokenResponseClient() {
		OAuth2AccessTokenResponseHttpMessageConverter accessTokenResponseConverter = new OAuth2AccessTokenResponseHttpMessageConverter();
		accessTokenResponseConverter.setTokenResponseConverter(new ShopifyOAuth2AccessTokenResponseConverter());
		RestTemplate restTemplate = new RestTemplate(Arrays.asList(
			new FormHttpMessageConverter(), accessTokenResponseConverter));
	
		restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
		
		oAuth2AccessTokenResponseClient = new DefaultAuthorizationCodeTokenResponseClient();
		oAuth2AccessTokenResponseClient.setRestOperations(restTemplate);
		
	}
	
	
	
	
	
	@Override
	public OAuth2AccessTokenResponse getTokenResponse(OAuth2AuthorizationCodeGrantRequest authorizationGrantRequest) {
		ClientRegistration currentRegistration = authorizationGrantRequest.getClientRegistration();
		OAuth2AuthorizationExchange currentExchange = authorizationGrantRequest.getAuthorizationExchange();
		
		String tokenUriTemplate = currentRegistration.getProviderDetails().getTokenUri();
		
		Map<String,Object> additionalParams = currentExchange.getAuthorizationRequest().getAdditionalParameters();
		
		String shopName = (additionalParams.containsKey(ShopifyOAuth2AuthorizationRequestResolver.SHOPIFY_SHOP_PARAMETER_KEY_FOR_TOKEN)) ? (String)additionalParams.get(ShopifyOAuth2AuthorizationRequestResolver.SHOPIFY_SHOP_PARAMETER_KEY_FOR_TOKEN) : null;
		
		if(shopName == null) {
			throw new RuntimeException("Shop name not found in the OAuth2AuthorizationRequest");
		}
		
		Map<String, String> uriVariables = new HashMap<>();
		uriVariables.put(ShopifyOAuth2AuthorizationRequestResolver.SHOPIFY_SHOP_PARAMETER_KEY_FOR_TOKEN, shopName);
		String tokenUri = UriComponentsBuilder
				.fromHttpUrl(tokenUriTemplate)
				.buildAndExpand(uriVariables)
				.toUriString();

		ClientRegistration newClientRegistration = ClientRegistration.withRegistrationId(currentRegistration.getRegistrationId())
	            .clientId(currentRegistration.getClientId())
	            .clientSecret(currentRegistration.getClientSecret())
	            .clientAuthenticationMethod(currentRegistration.getClientAuthenticationMethod())
	            .authorizationGrantType(currentRegistration.getAuthorizationGrantType())
	            .redirectUriTemplate(currentRegistration.getRedirectUriTemplate())
	            .scope(currentRegistration.getScopes())
	            .authorizationUri(currentRegistration.getProviderDetails().getAuthorizationUri())
	            .tokenUri(tokenUri)
	            .clientName(currentRegistration.getClientName())
	            .build();
		
		OAuth2AuthorizationCodeGrantRequest newGrantReq = new OAuth2AuthorizationCodeGrantRequest(newClientRegistration, currentExchange);

		logger.debug("Exchanging code for token with Shopify");

		OAuth2AccessTokenResponse resp = oAuth2AccessTokenResponseClient.getTokenResponse(newGrantReq);
		
		logger.debug("Obtained Shopify response for token");

		Map<String, Object> newAdditionalParameters = new HashMap<>();
		
		Map<String, Object> oldAdditionalParameters = resp.getAdditionalParameters();
		
		if(oldAdditionalParameters != null && oldAdditionalParameters.size() > 0) {
			newAdditionalParameters.putAll(oldAdditionalParameters);
		}
		
		if(newAdditionalParameters.containsKey(ShopifyOAuth2AuthorizationRequestResolver.SHOPIFY_SHOP_PARAMETER_KEY_FOR_TOKEN)) {
			newAdditionalParameters.replace(ShopifyOAuth2AuthorizationRequestResolver.SHOPIFY_SHOP_PARAMETER_KEY_FOR_TOKEN, shopName);

		} else {
			newAdditionalParameters.put(ShopifyOAuth2AuthorizationRequestResolver.SHOPIFY_SHOP_PARAMETER_KEY_FOR_TOKEN, shopName);
		}
				
		return responseWithModAddParams(resp, newAdditionalParameters);
	}
	
	// for testing purposes
	public void setRestOperations(RestOperations restOperations) {
		oAuth2AccessTokenResponseClient.setRestOperations(restOperations);
	}
	
	private OAuth2AccessTokenResponse responseWithModAddParams(OAuth2AccessTokenResponse response, Map<String, Object> params) {
		
		OAuth2AccessTokenResponse.Builder builder = OAuth2AccessTokenResponse.withResponse(response);
		
		builder.additionalParameters(params);
		
		return builder.build();
		
	}
	
	
	
}
