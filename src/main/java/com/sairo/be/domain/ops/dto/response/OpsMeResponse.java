package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "로그인한 운영자 정보")
public record OpsMeResponse(Long operatorId, String name, List<String> roles) {}
