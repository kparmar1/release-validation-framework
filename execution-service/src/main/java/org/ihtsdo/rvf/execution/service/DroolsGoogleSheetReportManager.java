package org.ihtsdo.rvf.execution.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.Permission;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.AddSheetRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.GridProperties;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.SpreadsheetProperties;
import com.google.api.services.sheets.v4.model.UpdateSheetPropertiesRequest;
import com.google.api.services.sheets.v4.model.UpdateSpreadsheetPropertiesRequest;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class DroolsGoogleSheetReportManager {

	private static final String APPLICATION_NAME = "RVF Report";
	private static final String DOMAIN = "ihtsdo.org";
	private static final String RAW = "RAW";

	private static final int DETAILS_MAX_COLS = 12;
	private static final int SUMMARY_MAX_COLS = 3;
	private static final int MAX_ROWS = 10000;
	private static final int MAX_ROWS_PER_CALL = 5000;

	@Value("${rvf.google.key}")
	private String secretKeyPath;

	@Value("${rvf.google.drive.dir.id}")
	private String driveDirectoryId;

	private Credential credentials;

	private Sheets sheetsService;

	private Drive driveService;

	private final static Logger LOGGER = LoggerFactory.getLogger(DroolsGoogleSheetReportManager.class);

	private Credential getCredentials() throws IOException {
		if (credentials == null) {
			LOGGER.info("Getting credential ...");
			credentials = GoogleCredential.fromStream(new FileInputStream(secretKeyPath)).createScoped(SheetsScopes.all());
			LOGGER.info("Credential obtained");
		}
		return credentials;
	}

	public Spreadsheet createSheet(String testFileName) throws GeneralSecurityException, IOException {
		try {
			if (sheetsService == null) {
				sheetsService = new Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), JacksonFactory.getDefaultInstance(), getCredentials())
						.setApplicationName(APPLICATION_NAME).build();
			}
			if (driveService == null) {
				driveService = new Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), JacksonFactory.getDefaultInstance(), getCredentials())
						.setApplicationName(APPLICATION_NAME).build();
			}
			Spreadsheet requestBody = new Spreadsheet();
			Sheets.Spreadsheets.Create request = sheetsService.spreadsheets().create(requestBody);
			Spreadsheet result = request.execute();

			Permission perm = new Permission()
					.setDomain(DOMAIN)
					.setType("domain")
					//.setType("anyone")
					.setKind("drive#permission")
					.setRole("writer");

			LOGGER.info("Created: {}", result.getSpreadsheetUrl());
			String spreadSheetId = result.getSpreadsheetId();
			driveService.permissions()
					.create(spreadSheetId, perm)
					.setSupportsTeamDrives(true)
					.execute();

			List<Request> requests = new ArrayList<>();
			requests.add(new Request().setUpdateSpreadsheetProperties(new UpdateSpreadsheetPropertiesRequest()
					.setProperties(new SpreadsheetProperties().setTitle(testFileName + " - " + new Date().getTime())).setFields("title")));

			SheetProperties sheetProperties = new SheetProperties().setTitle("Details").setGridProperties(new GridProperties().setRowCount(MAX_ROWS).setColumnCount(DETAILS_MAX_COLS));
			requests.add(new Request().setUpdateSheetProperties(new UpdateSheetPropertiesRequest().setProperties(sheetProperties).setFields("title,gridProperties")));

			SheetProperties properties = new SheetProperties().setTitle("Summary")
					.setSheetId(1).setGridProperties(new GridProperties().setRowCount(MAX_ROWS).setColumnCount(SUMMARY_MAX_COLS));
			requests.add(new Request().setAddSheet(new AddSheetRequest().setProperties(properties)));

			BatchUpdateSpreadsheetRequest batchUpdateSpreadsheetRequest = new BatchUpdateSpreadsheetRequest().setRequests(requests);
			batchUpdateSpreadsheetRequest.setRequests(requests);
			sheetsService.spreadsheets().batchUpdate(spreadSheetId, batchUpdateSpreadsheetRequest).execute();
			LOGGER.info("Spreadsheet shared with domain: {}", DOMAIN);

			moveFile(spreadSheetId);
			return result;
		} catch (Exception e) {
			LOGGER.error("Encounter error when creating new Sheet: {}", e);
			throw e;
		}
	}

	public void writeData(String spreadSheetId, String tabName, List<List<Object>> data) throws IOException {
		List<List<List<Object>>> dataChunks = chop(data, MAX_ROWS_PER_CALL);
		int counter = 0;
		String startCell = "'" + tabName + "'!A";
		for (List<List<Object>> dataChunk : dataChunks) {
			int row = MAX_ROWS_PER_CALL * counter + 1;
			if (row > MAX_ROWS) break;
			ValueRange valueRange = new ValueRange();
			valueRange.setValues(dataChunk);
			sheetsService.spreadsheets().values().update(spreadSheetId, startCell + row, valueRange)
					.setValueInputOption(RAW).execute();
			counter++;
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

	}

	public void moveFile(String fileId) throws IOException {
		// Retrieve the existing parents to remove
		com.google.api.services.drive.model.File file = driveService.files().get(fileId)
				.setFields("parents")
				//.setSupportsTeamDrives(true)
				.execute();
		StringBuilder previousParents = new StringBuilder();
		for (String parent : file.getParents()) {
			previousParents.append(parent);
			previousParents.append(',');
		}
		// Move the file to the new folder
		driveService.files().update(fileId, null)
				.setAddParents(driveDirectoryId)
				.setRemoveParents(previousParents.toString())
				.setSupportsTeamDrives(true)
				.setFields("id, parents")
				.execute();
	}

	private <T> List<List<T>> chop(List<T> list, final int L) {
		List<List<T>> parts = new ArrayList<List<T>>();
		final int N = list.size();
		for (int i = 0; i < N; i += L) {
			parts.add(new ArrayList<T>(
					list.subList(i, Math.min(N, i + L)))
			);
		}
		return parts;
	}

}