package com.leeworms.lock.controller;

import com.leeworms.lock.core.LockPurpose;
import com.leeworms.lock.core.LockManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/demo/form/consultation")
public class LockDemoController {

    private final LockManager lockManager;

    @PostMapping("/copy")
    public String copyConsultationForm(@RequestParam Long memberNo, @RequestParam Long formId) {
        return lockManager.executeWithLock(LockPurpose.CONSULTATION_FORM_SAVE, memberNo, () -> {
            log.info("상담 신청서 복사 처리 - memberNo={}, formId={}", memberNo, formId);
            simulateWork(500);
            return "Consultation form copied: memberNo=" + memberNo + ", formId=" + formId;
        });
    }

    @PostMapping("/pick/{consultationId}")
    public String pickMatching(@PathVariable Long consultationId, @RequestParam Long mentorNo) {
        return lockManager.executeWithResourceLock(LockPurpose.MATCHING_PICK, consultationId, () -> {
            log.info("멘토 픽 처리 - consultationId={}, mentorNo={}", consultationId, mentorNo);
            simulateWork(300);
            return "Matching picked: consultationId=" + consultationId + ", mentorNo=" + mentorNo;
        });
    }

    private void simulateWork(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
