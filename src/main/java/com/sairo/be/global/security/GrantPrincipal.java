package com.sairo.be.global.security;

import java.io.Serializable;

public record GrantPrincipal(Long userId, Long grantId, GrantType grantType, Long officeId)
    implements Serializable {}
