package ai.whitekey.repository;

import ai.whitekey.entity.HttpInjectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface HttpInjectionRepository extends JpaRepository<HttpInjectionEntity, UUID> {

    @Query("SELECT h FROM HttpInjectionEntity h JOIN FETCH h.credential ORDER BY h.id ASC")
    List<HttpInjectionEntity> findAllWithCredential();

    @Query("SELECT h FROM HttpInjectionEntity h JOIN FETCH h.credential WHERE h.credential.id = :credentialId ORDER BY h.id ASC")
    List<HttpInjectionEntity> findAllByCredentialId(@Param("credentialId") UUID credentialId);

    @Query("SELECT h FROM HttpInjectionEntity h JOIN FETCH h.credential WHERE h.credential.name = :name ORDER BY h.id ASC")
    List<HttpInjectionEntity> findAllByCredentialName(@Param("name") String name);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM HttpInjectionEntity h WHERE h.credential.id = :credentialId")
    int deleteByCredentialId(@Param("credentialId") UUID credentialId);
}
