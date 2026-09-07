package com.sairo.be.global.security;

import java.io.Serializable;

// Spring Session (JDBC-backed) Java-serializes the SecurityContext it stores in the
// session, so both this principal and StaffAuthentication must be Serializable.
public record StaffPrincipal(Long userId, Long membershipId, Long officeId, String name)
    implements Serializable {}
