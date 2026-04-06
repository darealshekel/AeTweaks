package com.aetweaks.mmmsync.model;

public record ServiceAccountCredentials(
        String client_email,
        String private_key,
        String token_uri)
{
}
