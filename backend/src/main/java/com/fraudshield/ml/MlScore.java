package com.fraudshield.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MlScore(double txnFraudProbability,
                      double senderMuleProbability,
                      double receiverMuleProbability,
                      double muleProbability,
                      double anomalyScore,
                      Map<String, Object> senderFeatures,
                      Map<String, Object> receiverFeatures,
                      List<String> reasons,
                      String modelVersion) {
}
