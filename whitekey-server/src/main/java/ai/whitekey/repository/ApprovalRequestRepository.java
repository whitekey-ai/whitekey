package ai.whitekey.repository;

import ai.whitekey.entity.ApprovalRequestEntity;
import ai.whitekey.entity.ApprovalRequestStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequestEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE ApprovalRequestEntity ar SET ar.status = :status WHERE ar.id = :id AND"
                    + " ar.status = ai.whitekey.entity.ApprovalRequestStatus.PENDING")
    int updatePendingStatus(@Param("id") UUID id, @Param("status") ApprovalRequestStatus status);

    @EntityGraph(attributePaths = "action")
    List<ApprovalRequestEntity> findAllByOrderByIdDesc(Pageable pageable);

    @EntityGraph(attributePaths = "action")
    List<ApprovalRequestEntity> findByStatusOrderByIdAsc(
            ApprovalRequestStatus status, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "DELETE FROM ApprovalRequestEntity ar WHERE ar.status <>"
                    + " ai.whitekey.entity.ApprovalRequestStatus.PENDING")
    int deleteResolved();
}
