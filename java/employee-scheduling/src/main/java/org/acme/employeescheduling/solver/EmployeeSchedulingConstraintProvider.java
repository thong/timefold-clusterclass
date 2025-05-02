package org.acme.employeescheduling.solver;

import static ai.timefold.solver.core.api.score.stream.Joiners.equal;
import static ai.timefold.solver.core.api.score.stream.Joiners.lessThanOrEqual;
import static ai.timefold.solver.core.api.score.stream.Joiners.overlapping;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Function;

import ai.timefold.solver.core.api.score.buildin.hardsoftbigdecimal.HardSoftBigDecimalScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.common.LoadBalance;

import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Shift;

public class EmployeeSchedulingConstraintProvider implements ConstraintProvider {

    private static int getMinuteOverlap(Shift shift1, Shift shift2) {
        // The overlap of two timeslot occurs in the range common to both timeslots.
        // Both timeslots are active after the higher of their two start times,
        // and before the lower of their two end times.
        LocalDateTime shift1Start = shift1.getStart();
        LocalDateTime shift1End = shift1.getEnd();
        LocalDateTime shift2Start = shift2.getStart();
        LocalDateTime shift2End = shift2.getEnd();
        return (int) Duration.between((shift1Start.isAfter(shift2Start)) ? shift1Start : shift2Start,
                (shift1End.isBefore(shift2End)) ? shift1End : shift2End).toMinutes();
    }

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                // Hard constraints
//                requiredSkill(constraintFactory),
//                noOverlappingShifts(constraintFactory),
                noRepeatedLocations(constraintFactory),
//                atLeast10HoursBetweenTwoShifts(constraintFactory),
                oneShiftPerDay(constraintFactory),
                unavailableEmployee(constraintFactory),
                allDaysAssigned(constraintFactory),
                maxClassSize(constraintFactory),
                // Soft constraints
//                undesiredDayForEmployee(constraintFactory),
//                desiredDayForEmployee(constraintFactory),
                desiredLocationForEmployee(constraintFactory),
                balanceEmployeeShiftAssignments(constraintFactory)
        };
    }

    Constraint requiredSkill(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> !shift.getEmployee().getSkills().contains(shift.getRequiredSkill()))
                .penalize(HardSoftBigDecimalScore.ONE_HARD)
                .asConstraint("Missing required skill");
    }

    Constraint noOverlappingShifts(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(Shift.class, equal(Shift::getEmployee),
                overlapping(Shift::getStart, Shift::getEnd))
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        EmployeeSchedulingConstraintProvider::getMinuteOverlap)
                .asConstraint("Overlapping shift");
    }

    Constraint noRepeatedLocations(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(Shift.class, equal(Shift::getEmployee),
                        equal(Shift::getLocation))
                .penalize(HardSoftBigDecimalScore.ONE_HARD)
                .asConstraint("Repeated location");
    }

    Constraint atLeast10HoursBetweenTwoShifts(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Shift.class, equal(Shift::getEmployee), lessThanOrEqual(Shift::getEnd, Shift::getStart))
                .filter((firstShift,
                        secondShift) -> Duration.between(firstShift.getEnd(), secondShift.getStart()).toHours() < 10)
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (firstShift, secondShift) -> {
                            int breakLength = (int) Duration.between(firstShift.getEnd(), secondShift.getStart()).toMinutes();
                            return (10 * 60) - breakLength;
                        })
                .asConstraint("At least 10 hours between 2 shifts");
    }

    Constraint oneShiftPerDay(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(Shift.class, equal(Shift::getEmployee),
                equal(shift -> shift.getStart().toLocalDate()))
                .penalize(HardSoftBigDecimalScore.ONE_HARD)
                .asConstraint("Max one shift per day");
    }

    Constraint unavailableEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getUnavailableDates)
                .filter(Shift::isOverlappingWithDate)
                .penalize(HardSoftBigDecimalScore.ONE_HARD, Shift::getOverlappingDurationInMinutes)
                .asConstraint("Unavailable employee");
    }

    Constraint allDaysAssigned(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(Shift::getEmployee, ConstraintCollectors.count())
                .penalize(HardSoftBigDecimalScore.ONE_HARD, ((employee, shiftCount) -> {
                    // count number of available, unassigned days
                    int unassignedCount = 3 - employee.getUnavailableDates().size() - shiftCount;
                    return Math.max(0, unassignedCount);
                }))
                .asConstraint("Shift assigned on every day employee is available");
    }

    Constraint maxClassSize(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(Shift::getId, ConstraintCollectors.toList())
                .penalize(HardSoftBigDecimalScore.ONE_HARD, ((shiftId, shifts) -> {
                    int maxSize = shifts.get(0).getMaxSize();
                    return maxSize == 0 ? 0 : Math.max(0, shifts.size() - maxSize);
                }))
                .asConstraint("Maximum class size");
    }

    Constraint undesiredDayForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getUndesiredDates)
                .filter(Shift::isOverlappingWithDate)
                .penalize(HardSoftBigDecimalScore.ONE_SOFT, Shift::getOverlappingDurationInMinutes)
                .asConstraint("Undesired day for employee");
    }

    Constraint desiredLocationForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getDesiredLocations)
                .filter(Shift::hasLocation)
                .reward(HardSoftBigDecimalScore.ONE_SOFT, Shift::getLocationScore)
                .asConstraint("Desired location for employee");
    }

    Constraint desiredDayForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getDesiredDates)
                .filter(Shift::isOverlappingWithDate)
                .reward(HardSoftBigDecimalScore.ONE_SOFT, Shift::getOverlappingDurationInMinutes)
                .asConstraint("Desired day for employee");
    }

    Constraint balanceEmployeeShiftAssignments(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(Shift::getEmployee, ConstraintCollectors.count())
                .complement(Employee.class, e -> 0) // Include all employees which are not assigned to any shift.c
                .groupBy(ConstraintCollectors.loadBalance((employee, shiftCount) -> employee,
                        (employee, shiftCount) -> shiftCount))
                .penalizeBigDecimal(HardSoftBigDecimalScore.ONE_SOFT, LoadBalance::unfairness)
                .asConstraint("Balance employee shift assignments");
    }

}
