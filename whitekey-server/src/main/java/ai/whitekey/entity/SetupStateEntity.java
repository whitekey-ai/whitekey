package ai.whitekey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "setup_state")
public class SetupStateEntity {

    public static final int GLOBAL_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private Integer id = GLOBAL_ID;

    @Column(name = "setup_mode", nullable = false)
    private boolean setupMode = false;

    protected SetupStateEntity() {}

    public SetupStateEntity(boolean setupMode) {
        this.setupMode = setupMode;
    }

    public boolean isSetupMode() {
        return setupMode;
    }

    public void setSetupMode(boolean setupMode) {
        this.setupMode = setupMode;
    }
}
