package ai.whitekey.entity;

import ai.whitekey.api.dto.ProtocolConstants;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "http_approval_request")
public class HttpApprovalRequestEntity extends ApprovalRequestEntity {

    protected HttpApprovalRequestEntity() {}

    public HttpApprovalRequestEntity(HttpActionEntity action) {
        super(action);
    }

    @Override
    public HttpActionEntity getAction() {
        return (HttpActionEntity) super.getAction();
    }

    @Override
    public String getType() {
        return ProtocolConstants.HTTP;
    }
}
