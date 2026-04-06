package com.miningtrackeraddon.mmm.render;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.miningtrackeraddon.MiningTrackerAddon;
import com.miningtrackeraddon.Reference;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

public final class MmmRemoteTextureCache
{
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<String, Identifier> TEXTURES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> PENDING = new ConcurrentHashMap<>();

    private MmmRemoteTextureCache()
    {
    }

    public static Identifier getOrRequest(String url)
    {
        if (url == null || url.isBlank())
        {
            return null;
        }

        Identifier existing = TEXTURES.get(url);
        if (existing != null)
        {
            return existing;
        }

        if (PENDING.putIfAbsent(url, Boolean.TRUE) == null)
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenAccept(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300)
                        {
                            PENDING.remove(url);
                            return;
                        }

                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client == null)
                        {
                            PENDING.remove(url);
                            return;
                        }

                        client.execute(() -> {
                            try
                            {
                                NativeImage image = NativeImage.read(new ByteArrayInputStream(response.body()));
                                Identifier identifier = Identifier.of(Reference.MOD_ID, "mmm/" + Integer.toHexString(url.hashCode()));
                                client.getTextureManager().registerTexture(identifier, new NativeImageBackedTexture(image));
                                TEXTURES.put(url, identifier);
                            }
                            catch (Exception exception)
                            {
                                MiningTrackerAddon.LOGGER.warn("Failed to decode MMM image {}", url, exception);
                            }
                            finally
                            {
                                PENDING.remove(url);
                            }
                        });
                    })
                    .exceptionally(throwable -> {
                        MiningTrackerAddon.LOGGER.warn("Failed to download MMM image {}", url, throwable);
                        PENDING.remove(url);
                        return null;
                    });
        }

        return null;
    }
}
