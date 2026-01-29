package com.microservice.loginsystem.dto;

import lombok.Data;

@Data
public class RefreshRequest {
    private String refreshToken;
}
