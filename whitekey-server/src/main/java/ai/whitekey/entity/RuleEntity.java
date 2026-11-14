package ai.whitekey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;

import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(
        name = "agent_rule",
        indexes = {
                @Index(name = "idx_rule_name", columnList = "name", unique = true)
        })
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class RuleEntity {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false)
    private RuleEffect effect;

    protected RuleEntity() {}

    protected RuleEntity(String name, RuleEffect effect) {
        this.name = name;
        this.effect = effect;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public RuleEffect getEffect() {
        return effect;
    }

    public void setEffect(RuleEffect effect) {
        this.effect = effect;
    }

    public abstract String getType();
}
