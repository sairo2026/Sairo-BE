package com.sairo.be.global.security;

import java.io.Serializable;

public record StaffPrincipal(Long userId, Long membershipId, Long officeId, String name)
    implements Serializable {}
