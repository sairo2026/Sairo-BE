package com.sairo.be.global.security.access;

public record AccessRequest(
    AccessPermission permission, AccessSubject subject, String method, String path) {}
