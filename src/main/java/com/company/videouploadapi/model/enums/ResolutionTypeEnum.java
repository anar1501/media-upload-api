package com.company.videouploadapi.model.enums;

import lombok.Getter;

@Getter
public enum ResolutionTypeEnum {
    P480("480p"), P720("720p");
    final String value;

    ResolutionTypeEnum(String value) {
        this.value = value;
    }

}
