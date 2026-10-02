package com.fabwatch.alarm.service;

import com.fabwatch.alarm.dto.AlarmSnapshot;
import com.fabwatch.alarm.entity.Alarm;
import com.fabwatch.alarm.repository.AlarmRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlarmQueryServiceImpl implements AlarmQueryService {

    private final AlarmRepository alarmRepository;

    @Override
    public Optional<AlarmSnapshot> findSnapshot(Long alarmId) {
        if (alarmId == null) {
            return Optional.empty();
        }
        return alarmRepository.findById(alarmId).map(AlarmQueryServiceImpl::toSnapshot);
    }

    private static AlarmSnapshot toSnapshot(Alarm alarm) {
        return new AlarmSnapshot(
                alarm.getId(),
                alarm.getEquipmentId(),
                alarm.getSensorId(),
                alarm.getAlarmType().name(),
                alarm.getSeverity().name(),
                alarm.getStatus().name(),
                alarm.getTriggerValue(),
                alarm.getThresholdValue(),
                alarm.getMessage(),
                alarm.getOccurredAt(),
                alarm.getResolveNote());
    }
}
