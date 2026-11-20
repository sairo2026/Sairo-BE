package com.sairo.be.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ClientIpResolverTest {

  private static final List<String> TRUSTED = List.of("127.0.0.0/8", "::1/128", "172.16.0.0/12");

  private final ClientIpResolver resolver = new ClientIpResolver(TRUSTED, "edge-secret");

  private static MockHttpServletRequest request(String peer) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(peer);
    return request;
  }

  @Test
  void trustedProxyCdnHeaderIsUsed() {
    MockHttpServletRequest request = request("172.18.0.1");
    request.addHeader("CF-Connecting-IP", "203.0.113.7");
    request.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.7, 104.16.0.1");

    assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
  }

  @Test
  void repeatedCdnHeaderIsDroppedSoTheClientCopyCannotWin() {
    MockHttpServletRequest request = request("172.18.0.1");
    request.addHeader("CF-Connecting-IP", "1.2.3.4");
    request.addHeader("CF-Connecting-IP", "203.0.113.7");
    request.addHeader("X-Forwarded-For", "203.0.113.7");

    assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
  }

  @Test
  void commaJoinedOrMalformedCdnHeaderIsDropped() {
    MockHttpServletRequest joined = request("172.18.0.1");
    joined.addHeader("CF-Connecting-IP", "1.2.3.4, 203.0.113.7");
    assertThat(resolver.resolve(joined)).isEqualTo("172.18.0.1");

    MockHttpServletRequest malformed = request("172.18.0.1");
    malformed.addHeader("CF-Connecting-IP", "evil.example.com");
    assertThat(resolver.resolve(malformed)).isEqualTo("172.18.0.1");
  }

  @Test
  void cdnHeaderNamingATrustedProxyAddressIsDropped() {
    MockHttpServletRequest request = request("172.18.0.1");
    request.addHeader("CF-Connecting-IP", "127.0.0.1");
    request.addHeader("X-Forwarded-For", "203.0.113.7");

    assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
  }

  @Test
  void repeatedEdgeClientHeaderIsDroppedEvenWithMatchingToken() {
    MockHttpServletRequest request = request("172.18.0.1");
    request.addHeader("CF-Connecting-IP", "198.51.100.5");
    request.addHeader("X-Vercel-Forwarded-For", "1.2.3.4");
    request.addHeader("X-Vercel-Forwarded-For", "203.0.113.7");
    request.addHeader("X-Sairo-Edge-Token", "edge-secret");

    assertThat(resolver.resolve(request)).isEqualTo("198.51.100.5");
  }

  @Test
  void untrustedPeerIgnoresEveryForwardingHeader() {
    MockHttpServletRequest request = request("198.51.100.9");
    request.addHeader("CF-Connecting-IP", "1.2.3.4");
    request.addHeader("X-Forwarded-For", "5.6.7.8");
    request.addHeader("X-Vercel-Forwarded-For", "9.9.9.9");
    request.addHeader("X-Sairo-Edge-Token", "edge-secret");

    assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
  }

  @Test
  void edgeClientHeaderNeedsMatchingToken() {
    MockHttpServletRequest withToken = request("172.18.0.1");
    withToken.addHeader("CF-Connecting-IP", "3.36.124.61");
    withToken.addHeader("X-Vercel-Forwarded-For", "203.0.113.7");
    withToken.addHeader("X-Sairo-Edge-Token", "edge-secret");
    assertThat(resolver.resolve(withToken)).isEqualTo("203.0.113.7");

    MockHttpServletRequest wrongToken = request("172.18.0.1");
    wrongToken.addHeader("CF-Connecting-IP", "198.51.100.5");
    wrongToken.addHeader("X-Vercel-Forwarded-For", "203.0.113.7");
    wrongToken.addHeader("X-Sairo-Edge-Token", "guess");
    assertThat(resolver.resolve(wrongToken)).isEqualTo("198.51.100.5");

    MockHttpServletRequest noToken = request("172.18.0.1");
    noToken.addHeader("CF-Connecting-IP", "198.51.100.5");
    noToken.addHeader("X-Vercel-Forwarded-For", "203.0.113.7");
    assertThat(resolver.resolve(noToken)).isEqualTo("198.51.100.5");
  }

  @Test
  void edgeClientHeaderIsIgnoredWhenNoTokenIsConfigured() {
    ClientIpResolver noToken = new ClientIpResolver(TRUSTED, "");
    MockHttpServletRequest request = request("172.18.0.1");
    request.addHeader("CF-Connecting-IP", "198.51.100.5");
    request.addHeader("X-Vercel-Forwarded-For", "203.0.113.7");
    request.addHeader("X-Sairo-Edge-Token", "");

    assertThat(noToken.resolve(request)).isEqualTo("198.51.100.5");
  }

  @Test
  void forwardedForUsesRightmostUntrustedEntryNotTheSpoofableLeftmost() {
    MockHttpServletRequest request = request("127.0.0.1");
    request.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.7, 172.18.0.5");

    assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
  }

  @Test
  void missingOrInvalidHeadersFallBackToPeer() {
    assertThat(resolver.resolve(request("172.18.0.1"))).isEqualTo("172.18.0.1");

    MockHttpServletRequest garbage = request("172.18.0.1");
    garbage.addHeader("CF-Connecting-IP", "not-an-ip");
    garbage.addHeader("X-Forwarded-For", "evil.example.com");
    assertThat(resolver.resolve(garbage)).isEqualTo("172.18.0.1");
  }

  @Test
  void ipv6ClientIsAccepted() {
    MockHttpServletRequest request = request("172.18.0.1");
    request.addHeader("CF-Connecting-IP", "2001:db8::1");

    assertThat(resolver.resolve(request)).isEqualTo("2001:db8::1");
  }

  @Test
  void filterPublishesResolvedAddressToClientRequestInfo() throws Exception {
    ClientIpFilter filter = new ClientIpFilter(TRUSTED, "");
    MockHttpServletRequest request = request("172.18.0.1");
    request.addHeader("CF-Connecting-IP", "203.0.113.7");
    request.addHeader("User-Agent", "agent");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(ClientRequestInfo.from(request).ipAddress()).isEqualTo("203.0.113.7");
  }

  @Test
  void clientRequestInfoFallsBackToPeerWithoutFilter() {
    MockHttpServletRequest request = request("198.51.100.9");
    request.addHeader("CF-Connecting-IP", "1.2.3.4");

    assertThat(ClientRequestInfo.from(request).ipAddress()).isEqualTo("198.51.100.9");
  }
}
