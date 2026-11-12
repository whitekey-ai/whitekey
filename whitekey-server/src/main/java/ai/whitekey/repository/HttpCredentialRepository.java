package ai.whitekey.repository;

import ai.whitekey.entity.HttpCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface HttpCredentialRepository extends JpaRepository<HttpCredentialEntity, UUID> {
}
