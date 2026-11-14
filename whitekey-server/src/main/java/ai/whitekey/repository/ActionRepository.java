package ai.whitekey.repository;

import ai.whitekey.entity.ActionEntity;
import ai.whitekey.entity.ActionStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ActionRepository extends JpaRepository<ActionEntity, UUID> {

    List<ActionEntity> findAllByOrderByIdDesc(Pageable pageable);

    List<ActionEntity> findByStatusOrderByIdDesc(ActionStatus status, Pageable pageable);

    List<ActionEntity> findByStatusOrderByIdAsc(ActionStatus status, Pageable pageable);

    long countByStatus(ActionStatus status);

    List<ActionEntity> findByStatusNot(ActionStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ActionEntity a SET a.status = :status WHERE a.id = :id")
    int updateStatus(
            @Param("id") UUID id,
            @Param("status") ActionStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE ActionEntity a SET a.status = :status "
                    + "WHERE a.id = :id AND a.status = ai.whitekey.entity.ActionStatus.AWAITING_APPROVAL")
    int resolvePending(
            @Param("id") UUID id,
            @Param("status") ActionStatus status);
}
