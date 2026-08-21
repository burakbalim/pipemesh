package io.pipemesh.console.web;

import io.pipemesh.console.apikey.ApiKey;
import io.pipemesh.console.apikey.ApiKeyService;
import io.pipemesh.console.apikey.IssuedApiKey;
import io.pipemesh.console.identity.ConsoleUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Managing the keys an SDK authenticates with. */
@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyService keys;

    public ApiKeyController(ApiKeyService keys) {
        this.keys = keys;
    }

    public record NewKeyRequest(String name) {
    }

    public record KeyView(String id, String name, String prefix, Instant createdAt, Instant lastUsedAt) {

        static KeyView of(ApiKey key) {
            return new KeyView(key.id(), key.name(), key.prefix(), key.createdAt(), key.lastUsedAt());
        }
    }

    /** The one response that carries a secret, and the only one that ever will. */
    public record IssuedKeyView(KeyView key, String secret) {
    }

    @GetMapping
    public List<KeyView> list(ConsoleUser user) {
        return keys.list(user.organizationId()).stream().map(KeyView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedKeyView issue(ConsoleUser user, @RequestBody NewKeyRequest request) {
        IssuedApiKey issued = keys.issue(user.organizationId(), request.name());
        return new IssuedKeyView(KeyView.of(issued.key()), issued.secret());
    }

    @DeleteMapping("/{id}")
    public void revoke(ConsoleUser user, @PathVariable String id) {
        if (!keys.revoke(id, user.organizationId())) {
            throw new UnknownApiKeyException(id);
        }
    }
}
