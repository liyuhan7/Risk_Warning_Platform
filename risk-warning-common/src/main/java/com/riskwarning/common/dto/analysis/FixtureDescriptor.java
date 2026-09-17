package com.riskwarning.common.dto.analysis;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixtureDescriptor {
    private String fixtureId;
    private String fixtureVersion;
    private String sourceFileSha256;
    private String fixtureSha256;
}
