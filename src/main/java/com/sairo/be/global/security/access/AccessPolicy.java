package com.sairo.be.global.security.access;

import com.sairo.be.global.error.BusinessException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class AccessPolicy {

  private final List<AccessStage> stages;

  @Autowired
  public AccessPolicy(ObjectProvider<AccessStage> providedStages) {
    this(providedStages.orderedStream().toList());
  }

  public AccessPolicy(List<AccessStage> providedStages) {
    this.stages = orderedStages(providedStages);
  }

  public List<AccessStageType> order() {
    return stages.stream().map(AccessStage::type).toList();
  }

  public void check(AccessRequest request) {
    if (request.permission() == AccessPermission.PUBLIC) {
      return;
    }
    for (AccessStage stage : stages) {
      AccessStageResult result = stage.evaluate(request);
      if (!result.allowed()) {
        throw new BusinessException(
            result.errorCode() != null ? result.errorCode() : stage.type().defaultDenial());
      }
    }
  }

  private static List<AccessStage> orderedStages(List<AccessStage> providedStages) {
    Map<AccessStageType, AccessStage> byType = new EnumMap<>(AccessStageType.class);
    for (AccessStage stage : providedStages) {
      if (byType.put(stage.type(), stage) != null) {
        throw new IllegalStateException("접근 판정 단계가 중복 정의됐습니다: " + stage.type());
      }
    }
    List<AccessStage> ordered = new ArrayList<>();
    for (AccessStageType type : AccessStageType.values()) {
      ordered.add(byType.getOrDefault(type, AccessStage.passThrough(type)));
    }
    return List.copyOf(ordered);
  }
}
