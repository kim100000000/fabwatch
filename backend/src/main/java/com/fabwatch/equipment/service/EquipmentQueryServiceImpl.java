package com.fabwatch.equipment.service;

import com.fabwatch.equipment.entity.Equipment;
import com.fabwatch.equipment.repository.EquipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentQueryServiceImpl implements EquipmentQueryService {

    private final EquipmentRepository equipmentRepository;

    @Override
    public boolean existsById(Long equipmentId) {
        return equipmentId != null && equipmentRepository.findById(equipmentId).isPresent();
    }

    @Override
    public Optional<String> findCodeById(Long equipmentId) {
        return equipmentId == null ? Optional.empty()
                : equipmentRepository.findById(equipmentId).map(Equipment::getCode);
    }

    @Override
    public Map<Long, String> findCodesByIds(Collection<Long> equipmentIds) {
        if (equipmentIds == null || equipmentIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = equipmentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return equipmentRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(Equipment::getId, Equipment::getCode));
    }
}
