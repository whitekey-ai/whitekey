package ai.whitekey.repository;

import ai.whitekey.entity.HttpRuleEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface HttpRuleRepository extends JpaRepository<HttpRuleEntity, UUID> {}
