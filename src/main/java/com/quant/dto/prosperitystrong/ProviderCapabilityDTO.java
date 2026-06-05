package com.quant.dto.prosperitystrong;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProviderCapabilityDTO {
    private String code;
    private String label;
    private boolean available;
    private boolean verified;
    private String role;
    private String message;
}
