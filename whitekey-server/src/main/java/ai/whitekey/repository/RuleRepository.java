package ai.whitekey.repository;

import ai.whitekey.entity.RuleEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleRepository extends JpaRepository<RuleEntity, UUID> {

    Optional<RuleEntity> findByName(String name);

    boolean existsByName(String name);

    @Query("SELECT r.name FROM RuleEntity r WHERE r.name = :base OR r.name LIKE :pattern ESCAPE '!'")
    List<String> findNamesWithPrefix(@Param("base") String base, @Param("pattern") String pattern);
}
