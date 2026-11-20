package com.sairo.be.global.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Resolves the client address behind a reverse proxy chain without trusting spoofable headers.
 *
 * <p>Forwarding headers are read only when the TCP peer is a configured trusted proxy. Otherwise
 * the peer address is used as is. For a trusted peer the sources are tried in this order:
 *
 * <ol>
 *   <li>the edge client header, only when it comes with the matching shared edge token
 *   <li>the CDN connecting-address header, which the CDN overwrites on every request. It is used
 *       only as a single valid IP literal that is not itself a trusted proxy address; a repeated
 *       header is dropped because the first copy may have been sent by the client
 *   <li>the right-most X-Forwarded-For entry that is not a trusted proxy
 *   <li>the TCP peer
 * </ol>
 */
public class ClientIpResolver {

  static final String CDN_HEADER = "CF-Connecting-IP";
  static final String EDGE_CLIENT_HEADER = "X-Vercel-Forwarded-For";
  static final String EDGE_TOKEN_HEADER = "X-Sairo-Edge-Token";
  static final String FORWARDED_FOR = "X-Forwarded-For";

  private final List<Cidr> trustedProxies;
  private final byte[] edgeToken;

  public ClientIpResolver(List<String> trustedProxyCidrs, String edgeToken) {
    List<Cidr> parsed = new ArrayList<>();
    for (String cidr : trustedProxyCidrs) {
      if (cidr != null && !cidr.isBlank()) {
        parsed.add(Cidr.parse(cidr.trim()));
      }
    }
    this.trustedProxies = List.copyOf(parsed);
    this.edgeToken =
        edgeToken == null || edgeToken.isBlank()
            ? null
            : edgeToken.trim().getBytes(StandardCharsets.UTF_8);
  }

  public String resolve(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    byte[] peerBytes = parseLiteral(peer);
    if (peerBytes == null || !isTrusted(peerBytes)) {
      return peer;
    }

    String presented = request.getHeader(EDGE_TOKEN_HEADER);
    if (edgeToken != null
        && presented != null
        && MessageDigest.isEqual(edgeToken, presented.trim().getBytes(StandardCharsets.UTF_8))) {
      String edgeClient = single(request, EDGE_CLIENT_HEADER);
      if (edgeClient != null) {
        return edgeClient;
      }
    }

    String cdnClient = single(request, CDN_HEADER);
    if (cdnClient != null && !isTrusted(parseLiteral(cdnClient))) {
      return cdnClient;
    }

    String forwarded = request.getHeader(FORWARDED_FOR);
    if (forwarded != null) {
      String[] hops = forwarded.split(",");
      for (int i = hops.length - 1; i >= 0; i--) {
        String hop = hops[i].trim();
        byte[] bytes = parseLiteral(hop);
        if (bytes == null) {
          break;
        }
        if (!isTrusted(bytes)) {
          return hop;
        }
      }
    }
    return peer;
  }

  private static String single(HttpServletRequest request, String name) {
    Enumeration<String> values = request.getHeaders(name);
    if (values == null || !values.hasMoreElements()) {
      return null;
    }
    String value = values.nextElement();
    if (value == null || values.hasMoreElements()) {
      return null;
    }
    String trimmed = value.trim();
    return parseLiteral(trimmed) == null ? null : trimmed;
  }

  private boolean isTrusted(byte[] address) {
    for (Cidr cidr : trustedProxies) {
      if (cidr.contains(address)) {
        return true;
      }
    }
    return false;
  }

  /** Parses an IP literal only. Returns null for anything else so no DNS lookup can happen. */
  static byte[] parseLiteral(String value) {
    if (value == null || value.isEmpty() || value.length() > 45) {
      return null;
    }
    boolean v6 = value.indexOf(':') >= 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean ok = (c >= '0' && c <= '9') || c == '.';
      if (v6) {
        ok = ok || c == ':' || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      }
      if (!ok) {
        return null;
      }
    }
    if (!v6 && value.split("\\.", -1).length != 4) {
      return null;
    }
    try {
      return InetAddress.getByName(value).getAddress();
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private record Cidr(byte[] network, int prefix) {

    static Cidr parse(String text) {
      int slash = text.indexOf('/');
      String host = slash < 0 ? text : text.substring(0, slash);
      byte[] bytes = parseLiteral(host);
      if (bytes == null) {
        throw new IllegalArgumentException("Invalid trusted proxy address: " + text);
      }
      int prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(text.substring(slash + 1));
      if (prefix < 0 || prefix > bytes.length * 8) {
        throw new IllegalArgumentException("Invalid trusted proxy prefix: " + text);
      }
      return new Cidr(bytes, prefix);
    }

    boolean contains(byte[] address) {
      if (address.length != network.length) {
        return false;
      }
      int full = prefix / 8;
      for (int i = 0; i < full; i++) {
        if (address[i] != network[i]) {
          return false;
        }
      }
      int rest = prefix % 8;
      if (rest == 0) {
        return true;
      }
      int mask = (0xFF << (8 - rest)) & 0xFF;
      return (address[full] & mask) == (network[full] & mask);
    }
  }
}
