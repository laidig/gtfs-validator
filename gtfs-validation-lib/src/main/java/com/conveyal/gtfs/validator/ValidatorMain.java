package com.conveyal.gtfs.validator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.model.Agency;
import org.onebusaway.gtfs.serialization.GtfsReader;

import com.conveyal.gtfs.model.InvalidValue;
import com.conveyal.gtfs.model.ValidationResult;
import com.conveyal.gtfs.service.CalendarDateVerificationService;
import com.conveyal.gtfs.service.GtfsValidationService;
import com.conveyal.gtfs.service.StatisticsService;
import com.conveyal.gtfs.service.impl.GtfsStatisticsService;

/**
 * Provides a main class for running the GTFS validator.
 * @author mattwigway
 * @author laidig
 */
public class ValidatorMain {
	public static void main(String[] args) {
		if (args.length != 1) {
			System.err.println("Usage: gtfs-validator /path/to/gtfs.zip");
			System.exit(-1);
		}
		
		// disable logging; we don't need log messages from the validator printed to the console
		// Messages from inside OBA will still be printed, which is fine
		// loosely based upon http://stackoverflow.com/questions/470430
		for (Handler handler : Logger.getLogger("").getHandlers()) {
			handler.setLevel(Level.OFF);
		}
		
		File inputGtfs = new File(args[0]);
		
		System.err.println("Reading GTFS from " + inputGtfs.getPath());
		
		GtfsRelationalDaoImpl dao = new GtfsRelationalDaoImpl();
		
		GtfsReader reader = new GtfsReader();
		
		try {
			reader.setInputLocation(inputGtfs);
			reader.setEntityStore(dao);
			reader.run();
		} catch (IOException e) {
			System.err.println("Could not read file " + inputGtfs.getPath() +
					"; does it exist and is it readable?");
			System.exit(-1);
		}

		System.err.println("Read GTFS");
		
		if (dao.getAllTrips().size() == 0){
			System.err.println("No Trips Found in GTFS, exiting");
			System.exit(-1);
		}
				
		GtfsValidationService validationService = new GtfsValidationService(dao);
			
		CalendarDateVerificationService calendarDateVerService = new CalendarDateVerificationService(dao);
		
		System.err.println("Validating routes");
		ValidationResult routes = validationService.validateRoutes();
		
		System.err.println("Validating trips");
		ValidationResult trips = validationService.validateTrips();
		
		System.err.println("Checking for duplicate stops");
		ValidationResult stops = validationService.duplicateStops();
		
		System.err.println("Checking for problems with shapes");
		ValidationResult shapes = validationService.listReversedTripShapes();
		shapes.append(validationService.listStopsAwayFromShape(130.0));
		
		System.err.println("Checking for dates with no trips");
		ValidationResult dates = calendarDateVerService.getCalendarProblems(); 
		
		System.err.println("Calculating statistics");
		
		// Make the report
		StringBuilder sb = new StringBuilder(256);
		sb.append("# Validation report for ");

		List<Agency> agencies = new ArrayList<>(dao.getAllAgencies());
		int size = agencies.size();
		
		for (int i = 0; i < size; i++) {
			sb.append(agencies.get(i).getName());
			if (size - i == 1) {
				// append nothing, we're at the end
			}
			else if (size - i == 2)
				// the penultimate agency, use and
				// we can debate the relative merits of the Oxford comma at a later date, however not using has the
				// advantage that the two-agency case (e.g. BART and AirBART, comma would be gramatically incorrect)
				// is also handled.
				sb.append(" and ");
			else
				sb.append(", ");
		}
		
		System.out.println(sb.toString());
		
		// generate and display feed statistics
		System.out.println("## Feed statistics");
		StatisticsService stats = new GtfsStatisticsService(dao);
		
		System.out.println("- " + stats.getAgencyCount() + " agencies");
		System.out.println("- " + stats.getRouteCount() + " routes");
		System.out.println("- " + stats.getTripCount() + " trips");
		System.out.println("- " + stats.getStopCount() + " stops");
		System.out.println("- " + stats.getStopTimesCount() + " stop times");
		
		Optional<Date> calDateStart = stats.getCalendarDateStart();
		Date calSvcStart = stats.getCalendarServiceRangeStart();
		Optional<Date> calDateEnd = stats.getCalendarDateEnd();
		Date calSvcEnd = stats.getCalendarServiceRangeEnd();
		
		Date feedSvcStart = getEarliestDate(calDateStart, calSvcStart);
		Date feedSvcEnd = getLatestDate(calDateEnd, calSvcEnd);
		
		// need an extra newline at the start so it doesn't get appended to the last list item if we let
		// a markdown processor loose on the output.
		System.out.println("\nFeed has service from " +	feedSvcStart +" to " + feedSvcEnd);
								
		System.out.println("## Validation Results");
		System.out.println("- Routes: " + getValidationSummary(routes));
		System.out.println("- Trips: " + getValidationSummary(trips));
		System.out.println("- Stops: " + getValidationSummary(stops));
		System.out.println("- Shapes: " + getValidationSummary(shapes));
		System.out.println("- Dates: " + getValidationSummary(dates));
		
		System.out.println("\n### Routes");
		System.out.println(getValidationReport(routes));
		// no need for another line feed here to separate them, as one is added by getValidationReport and another by
		// System.out.println
		
		System.out.println("\n### Trips");
		System.out.println(getValidationReport(trips));
		
		System.out.println("\n### Stops");
		System.out.println(getValidationReport(stops));
		
		System.out.println("\n### Shapes");
		System.out.println(getValidationReport(shapes));
		
		System.out.println("\n### Dates");
		System.out.println(getValidationReport(dates));
		
		System.out.println("\n### Active Calendars for the next 30 days");
		System.out.println(calendarDateVerService.getTripDataForEveryDay());
	}
	
	/**
	 * Return a single-line summary of a ValidationResult
	 */
	public static String getValidationSummary(ValidationResult result) {
		return result.invalidValues.size() + " errors/warnings";
	}
	
	/**
	 * Return a human-readable, markdown-formatted multiline exhaustive report on a ValidationResult.
	 */
	public static String getValidationReport(ValidationResult result) {
		if (result.invalidValues.size() == 0)
			return "Hooray! No errors here (at least, none that we could find).\n";
		
		StringBuilder sb = new StringBuilder(256);
		int i =0;
		int MAX_PRINT = 128;
		
		// loop over each invalid value, and take advantage of InvalidValue.toString to create a line about the error
		for (InvalidValue v : result.invalidValues) {
			i++;
			if (i > MAX_PRINT){
				sb.append("And Many More...");
				break;
			}
			sb.append("- ");
			sb.append(v.toString());
			sb.append('\n');
			
		}
		
		return sb.toString();
	}
	
	static Date getEarliestDate(Optional<Date> o, Date d){
		if (o.isPresent()){
			d = o.get().before(d) ? o.get() : d;
		}
		return d;
	}
	
	static Date getLatestDate(Optional<Date> o, Date d){
		if(o.isPresent()){
			d = o.get().after(d) ? o.get(): d;
		}
		return d;
	}

}

