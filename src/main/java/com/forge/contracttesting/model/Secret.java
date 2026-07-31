package com.forge.contracttesting.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document(collection = "secrets")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Secret {

    @Id
    private String id;

    @Field("key")
    private String key;

    @Field("value")
    private String value;
}
