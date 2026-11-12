package ai.whitekey.repository;

import ai.whitekey.entity.ActionStatus;
import ai.whitekey.entity.HttpActionEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.UUID;

public interface HttpActionRepository extends JpaRepository<HttpActionEntity, UUID> {

    @Query(
            "SELECT h FROM HttpActionEntity h"
                    + " WHERE (:status IS NULL OR h.status = :status)"
                    + " AND (:method IS NULL OR h.method = :method)"
                    + " AND (:host IS NULL OR h.host = :host)"
                    + " ORDER BY h.id DESC")
    List<HttpActionEntity> findFiltered(
            @Param("status") ActionStatus status,
            @Param("method") HttpMethod method,
            @Param("host") String host,
            Pageable pageable);
}
