/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2023 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.production.service;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.production.db.Machine;
import com.axelor.apps.production.db.OperationOrder;
import com.axelor.apps.production.db.ProdProcessLine;
import com.axelor.apps.production.db.WorkCenter;
import com.axelor.apps.production.db.WorkCenterGroup;
import com.axelor.apps.production.db.repo.ProdProcessLineRepository;
import com.axelor.apps.production.db.repo.WorkCenterRepository;
import com.axelor.apps.production.exceptions.ProductionExceptionMessage;
import com.axelor.db.JPA;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;

public class ProdProcessLineServiceImpl implements ProdProcessLineService {

  protected ProdProcessLineRepository prodProcessLineRepo;
  protected WorkCenterService workCenterService;

  @Inject
  public ProdProcessLineServiceImpl(
      ProdProcessLineRepository prodProcessLineRepo, WorkCenterService workCenterService) {
    this.prodProcessLineRepo = prodProcessLineRepo;
    this.workCenterService = workCenterService;
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void setWorkCenterGroup(ProdProcessLine prodProcessLine, WorkCenterGroup workCenterGroup)
      throws AxelorException {
    prodProcessLine = copyWorkCenterGroup(prodProcessLine, workCenterGroup);
    WorkCenter workCenter =
        workCenterService.getMainWorkCenterFromGroup(prodProcessLine.getWorkCenterGroup());
    prodProcessLine.setWorkCenter(workCenter);
    prodProcessLine.setDurationPerCycle(
        workCenterService.getMachineDurationFromWorkCenter(workCenter));
    prodProcessLine.setHumanDuration(workCenterService.getHumanDurationFromWorkCenter(workCenter));
    prodProcessLine.setMinCapacityPerCycle(
        workCenterService.getMinCapacityPerCycleFromWorkCenter(workCenter));
    prodProcessLine.setMaxCapacityPerCycle(
        workCenterService.getMaxCapacityPerCycleFromWorkCenter(workCenter));
  }

  /**
   * Create a work center group from a template. Since a template is also a work center group, we
   * copy and set template field to false.
   */
  protected ProdProcessLine copyWorkCenterGroup(
      ProdProcessLine prodProcessLine, WorkCenterGroup workCenterGroup) {
    WorkCenterGroup workCenterGroupCopy = JPA.copy(workCenterGroup, false);
    workCenterGroupCopy.setWorkCenterGroupModel(workCenterGroup);
    workCenterGroupCopy.setTemplate(false);
    workCenterGroup.getWorkCenterSet().forEach((workCenterGroupCopy::addWorkCenterSetItem));

    prodProcessLine.setWorkCenterGroup(workCenterGroupCopy);
    return prodProcessLineRepo.save(prodProcessLine);
  }

  @Override
  public long computeEntireCycleDuration(
      OperationOrder operationOrder, ProdProcessLine prodProcessLine, BigDecimal qty)
      throws AxelorException {
    WorkCenter workCenter = prodProcessLine.getWorkCenter();

    Pair<Long, BigDecimal> durationNbCyclesPair =
        getDurationNbCyclesPair(workCenter, prodProcessLine, qty);
    long duration = durationNbCyclesPair.getLeft().longValue();
    BigDecimal nbCycles = durationNbCyclesPair.getRight();

    BigDecimal machineDurationPerCycle =
        new BigDecimal(Optional.ofNullable(prodProcessLine.getDurationPerCycle()).orElse(0l));
    BigDecimal humanDurationPerCycle =
        new BigDecimal(Optional.ofNullable(prodProcessLine.getHumanDuration()).orElse(0l));
    BigDecimal maxDurationPerCycle =
        getMaxDuration(Arrays.asList(machineDurationPerCycle, humanDurationPerCycle));

    long plannedDuration = 0;
    long machineDuration = duration + nbCycles.multiply(machineDurationPerCycle).longValue();
    long humanDuration = nbCycles.multiply(humanDurationPerCycle).longValue();

    if (machineDurationPerCycle.equals(maxDurationPerCycle)) {
      plannedDuration = machineDuration;
    } else if (humanDurationPerCycle.equals(maxDurationPerCycle)) {
      plannedDuration = humanDuration;
    }

    if (operationOrder != null) {
      operationOrder.setPlannedMachineDuration(machineDuration);
      operationOrder.setPlannedHumanDuration(humanDuration);
    }

    return plannedDuration;
  }

  protected Pair<Long, BigDecimal> getDurationNbCyclesPair(
      WorkCenter workCenter, ProdProcessLine prodProcessLine, BigDecimal qty)
      throws AxelorException {
    long duration = 0;
    if (prodProcessLine.getWorkCenter() == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(ProductionExceptionMessage.PROD_PROCESS_LINE_MISSING_WORK_CENTER),
          prodProcessLine.getProdProcess() != null
              ? prodProcessLine.getProdProcess().getCode()
              : "null",
          prodProcessLine.getName());
    }

    BigDecimal maxCapacityPerCycle = prodProcessLine.getMaxCapacityPerCycle();

    BigDecimal nbCycles;
    if (maxCapacityPerCycle.compareTo(BigDecimal.ZERO) == 0) {
      nbCycles = qty;
    } else {
      nbCycles = qty.divide(maxCapacityPerCycle, 0, RoundingMode.UP);
    }

    int workCenterTypeSelect = workCenter.getWorkCenterTypeSelect();

    if (workCenterTypeSelect == WorkCenterRepository.WORK_CENTER_TYPE_MACHINE
        || workCenterTypeSelect == WorkCenterRepository.WORK_CENTER_TYPE_BOTH) {
      Machine machine = workCenter.getMachine();
      if (machine == null) {
        throw new AxelorException(
            workCenter,
            TraceBackRepository.CATEGORY_MISSING_FIELD,
            I18n.get(ProductionExceptionMessage.WORKCENTER_NO_MACHINE),
            workCenter.getName());
      }
      duration += workCenter.getStartingDuration();
      duration += workCenter.getEndingDuration();
      duration +=
          nbCycles
              .subtract(new BigDecimal(1))
              .multiply(new BigDecimal(workCenter.getSetupDuration()))
              .longValue();
    }

    return Pair.of(Long.valueOf(duration), nbCycles);
  }

  protected BigDecimal getMaxDuration(List<BigDecimal> durations) {
    return !CollectionUtils.isEmpty(durations) ? Collections.max(durations) : BigDecimal.ZERO;
  }
}
