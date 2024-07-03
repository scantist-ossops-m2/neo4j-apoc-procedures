package apoc.coll;

import apoc.Extended;
import apoc.util.CollectionUtils;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserFunction;
import org.neo4j.values.storable.DurationValue;

import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

@Extended
public class CollExtended {

    @UserFunction
    @Description("apoc.coll.avgDuration([duration('P2DT3H'), duration('PT1H45S'), ...]) -  returns the average of a list of duration values")
    public DurationValue avgDuration(@Name("durations") List<DurationValue> list) {
        if (CollectionUtils.isEmpty(list)) return null;

        long count = 0;

        double monthsRunningAvg = 0;
        double daysRunningAvg = 0;
        double secondsRunningAvg = 0;
        double nanosRunningAvg = 0;
        for (DurationValue duration : list) {
            count++;
            monthsRunningAvg += (duration.get(ChronoUnit.MONTHS) - monthsRunningAvg) / count;
            daysRunningAvg  += (duration.get(ChronoUnit.DAYS) - daysRunningAvg) / count;
            secondsRunningAvg  += (duration.get(ChronoUnit.SECONDS) - secondsRunningAvg) / count;
            nanosRunningAvg  += (duration.get(ChronoUnit.NANOS) - nanosRunningAvg) / count;
        }

        return DurationValue.approximate(monthsRunningAvg, daysRunningAvg, secondsRunningAvg, nanosRunningAvg)
                .normalize();
    }

    @UserFunction
    @Description("apoc.coll.fillObject(item, size) - returns a list of equals items with the given size")
    public List<Object> fillObject(@Name(value = "item", defaultValue = "null") Object item, 
                                   @Name(value = "size", defaultValue = "0") long size) {
        return Collections.nCopies((int) size, item);
    }
}
