package ai.whitekey.repository;

import ai.whitekey.entity.RuleEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RuleRepository extends JpaRepository<RuleEntity, UUID> {

    Optional<RuleEntity> findByName(String name);

    boolean existsByName(String name);
}
