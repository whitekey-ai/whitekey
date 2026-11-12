package ai.whitekey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;

import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "agent_rule")
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class RuleEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false)
    private RuleEffect effect;

    @Column(name = "description")
    private String description;

    protected RuleEntity() {}

    protected RuleEntity(RuleEffect effect, String description) {
        this.effect = effect;
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public RuleEffect getEffect() {
        return effect;
    }

    public void setEffect(RuleEffect effect) {
        this.effect = effect;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public abstract String getType();
}
