package main.app;

import com.monitorjbl.xlsx.StreamingReader;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import org.apache.poi.ss.usermodel.*;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static main.app.App.*;
import static main.app.DF.Col_types.SKP;

public class BaseSin extends BaseAccum {
    Map<String, ArrayList<Object[]>> dfMapped = new HashMap<>();
    Map<String, Integer> nrowMapped = new HashMap<>();
    Object[] refProgrammesRow;
    String numPolice = "";
    char delim = ';';
    String pays = "";
    String path = "";
    public static void main(String[] args) throws IOException {
        long startTime = System.nanoTime();long endTime;long duration;long minutes;long seconds;
//        DF fic_FRA = new BaseFic(wd + "source FIC/SPB France/","FIC France");
//        DF fic_ITA = new BaseFic(wd + "source FIC/SPB Italie/","DB Claims Italie");
        BaseSin base_aux = new BaseSin(wd+"source SIN/SPB France/","France","SPB France / Wakam",true);
        base_aux.print(10);
        System.out.println(base_aux.nrow);
        System.out.println(Arrays.toString(base_aux.r(530000)));

//        DF sin_POL = new BaseSin(wd + "source SIN/SPB Pologne/","Pologne","SPB Pologne");
//        sin_POL.print(10);
//        System.out.println(sin_POL.nrow);
//        System.out.println(Arrays.toString(sin_POL.r(130000)));
        endTime = System.nanoTime();
        long elapsedTime = endTime - startTime;

        minutes = TimeUnit.NANOSECONDS.toMinutes(elapsedTime);
        seconds = TimeUnit.NANOSECONDS.toSeconds(elapsedTime) - TimeUnit.MINUTES.toSeconds(minutes);

        System.out.println("Elapsed Time: " + minutes + " minutes " + seconds + " seconds");

    }
    public BaseSin(String path) throws IOException {
        this.fullPath = path;
        // Extracting the filename and keys to find the matching referential row
        fileName = new File(path).getName().replace(".xlsm", "")
                .replace(".xlsx", "").toLowerCase();
        System.out.println(fileName);

        String[] keys = fileName.split("_");
        key_sin = keys[0];
        referentialRow = getReferentialRow(keys);

        InputStream is = Files.newInputStream(new File(path).toPath());
        Workbook workbook = StreamingReader.builder()
                .rowCacheSize(1)
                .bufferSize(4096)
                .open(is);

        String sheet_name;
        if (workbook.getNumberOfSheets() == 1) {
            sheet_name = workbook.getSheetName(0);
        } else {
            sheet_name = null;
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                String tempSheetName = workbook.getSheetName(i);
                if (tempSheetName != null && tempSheetName.toLowerCase().contains("sinistre")) {
                    sheet_name = tempSheetName;
                    break;
                }
            }

            if (sheet_name == null) {
                sheet_name = workbook.getSheetName(0);
            }
        }
        Sheet sheet = workbook.getSheet(sheet_name);
        Iterator<Row> rowIter = sheet.rowIterator();
        Row row = rowIter.next();
        nrow = sheet.getLastRowNum();
        ncol = row.getLastCellNum();
        header = new String[ncol];

        int i = 0;
        for (Cell c : row) {
            header[i] = c.getStringCellValue().toLowerCase();
            i++;
        }
        String[] cols = Arrays.copyOf(ref_triangle.header, ref_triangle.header.length);
        String[] cols_to_take = getColsToTake();

        coltypes = new Col_types[ncol];
        for (i = 0; i < ncol; i++) {
            if (check_in(header[i], cols_to_take)) {
                int exactPosition = find_in_arr_first_index(referentialRow,header[i]);
                coltypes[i] = cols[exactPosition].startsWith("date") ? Col_types.DAT : cols[exactPosition].startsWith("montant") ? Col_types.DBL : Col_types.STR;
            } else {
                coltypes[i] = SKP;
            }
        }

        ncol = get_len(coltypes);
        df = new ArrayList<>(ncol);
        this.df_populate(coltypes);

        String dateFormatString = referentialRow[8].toString();
        SimpleDateFormat dateFormatter = getDateFormatter(dateFormatString);

        int col_iterator;
        int row_number = 0;
        while(rowIter.hasNext()) {
            row = rowIter.next();
            col_iterator = 0;
            for (int c = 0; c < this.header.length; c++) {
                if (coltypes[c] != SKP) {
                    Cell cell_i = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell_i == null) {
                        switch(coltypes[c]) {
                            case STR -> df.get(col_iterator)[row_number] = "";
                            case DBL -> df.get(col_iterator)[row_number] = 0d;
                            case DAT -> df.get(col_iterator)[row_number] = NA_DAT;
                        }
                        col_iterator++;
                        continue;
                    }
                    //System.out.println(cell_i);
                    df.get(col_iterator)[row_number] = parseCell(cell_i, coltypes[c], dateFormatter);
                    col_iterator++;
                }
            }
            row_number++;
            rowchecker++;

        }
        headerAndColtypesDropSKP();
        header_unify();
        date_autofill_agg();
        populateUniqueStatuts();
        populateUniqueNumPoliceValues();
        computeMinMaxDatesForPolicies();    }
    public BaseSin(String path, String pays, String mappingColDefault) throws IOException {
        this.source = true;
        this.pays = pays;
        this.path = path;
        this.referentialRow = getReferentialRow(new String[]{"source"});

        List<File> fileList = Arrays.asList(Objects.requireNonNull(new File(path).listFiles()));
        if (fileList.isEmpty()) return;

        if (pays.equals("Pologne")) {
            delim = '\t';
        }
        if (pays.equals("France")) {
            delim = '|';
        }

        int dim = computeDimSIN();
        System.out.println(dim);

        CsvParserSettings settings = new CsvParserSettings();
        settings.setDelimiterDetectionEnabled(true, delim);
        settings.trimValues(true);

        int i = 0;
        boolean initialized = false;

        for (File file : fileList) {
            String mapping_col = "";
            if (file.toString().contains("FRMP")) {
                mapping_col = "SPB France / ONEY";
            } else {
                mapping_col = mappingColDefault;
            }
            try (Reader inputReader = Files.newBufferedReader(file.toPath(), Charset.forName(encoding))) {
                CsvParser parser = new CsvParser(settings);
                List<String[]> parsedRows = parser.parseAll(inputReader);
                Iterator<String[]> rows = parsedRows.iterator();

                if (!initialized) {
                    header = rows.next();
                    header = Arrays.stream(header)
                            .filter(h -> h != null && !h.trim().isEmpty())
                            .toArray(String[]::new);
                    ncol = header.length;

                    boolean[] cols_kept = this.mapColnamesAndKeepNeededMain(mapping_col);
                    header_unify();
                    coltypes_populate(cols_kept);

                    nrow = dim;
                    assert (coltypes.length == parsedRows.get(0).length);
                    ncol = get_len(coltypes);
                    df = new ArrayList<>(get_len(coltypes));
                    this.df_populate(coltypes);

                    initialized = true;
                } else {
                    rows.next(); // Skipping the header for all subsequent files
                }

                while (rows.hasNext()) {
                    int j = 0;
                    int k = 0;
                    String[] parsedRow = rows.next();
                    parsedRow = Arrays.copyOf(parsedRow, header.length);
                    for (String s : parsedRow) {
                        if (coltypes[k] != SKP) {
                            df.get(j)[i] = get_lowercase_cell_of_type(s, coltypes[k], dateDefault);
                            j++;
                        }
                        k++;
                    }
                    i++;
                }

            }
        }
        headerAndColtypesDropSKP();
        date_autofill_agg();
        remove_leading_zeros();
        populateUniqueStatuts();
        populateUniqueNumPoliceValues();
        computeMinMaxDatesForPolicies();
    }
    public BaseSin(String path, String pays, String mappingColDefault, boolean news) throws IOException {
        this.source = true;
        this.pays = pays;
        this.path = path;
        this.referentialRow = getReferentialRow(new String[]{"source"});

        List<File> fileList = Arrays.asList(Objects.requireNonNull(new File(path).listFiles()));
        if (fileList.isEmpty()) return;

        if (pays.equals("Pologne")) {
            delim = '\t';
        }
        if (pays.equals("France")) {
            delim = '|';
        }

//        int dim = computeDimSIN();
//        System.out.println(dim);

        CsvParserSettings settings = new CsvParserSettings();
        settings.setDelimiterDetectionEnabled(true, delim);
        settings.trimValues(true);

        int i;
        boolean initialized = false;
        String[] headerRef = {""};
        int[] headerIndexes = {0};

        for (File file : fileList) {
            String police = extractKeyFromFileName(file.getName());
            uniqueNumPoliceValues.add(police);
            String mapping_col = "";
            if (file.toString().contains("FRMP")) {
                mapping_col = "SPB France / ONEY";
            } else {
                mapping_col = mappingColDefault;
            }
            try (Reader inputReader = Files.newBufferedReader(file.toPath(), Charset.forName(encoding))) {
                CsvParser parser = new CsvParser(settings);
                List<String[]> parsedRows = parser.parseAll(inputReader);
                Iterator<String[]> rows = parsedRows.iterator();

                if (!initialized) {
                    header = rows.next();
                    header = Arrays.stream(header)
                            .filter(h -> h != null && !h.trim().isEmpty())
                            .toArray(String[]::new);
                    ncol = header.length;
                    headerRef = Arrays.copyOf(header,ncol);

                    boolean[] cols_kept = this.mapColnamesAndKeepNeededMain(mapping_col);
                    header_unify();
                    coltypes_populate(cols_kept);

                    Integer nr = csv_get_nrows(file.getPath(), delim);
                    nrowMapped.put(police,nr);

                    ncol = get_len(coltypes);
                    dfMapped.put(police,new ArrayList<>(ncol));
                    this.df_populateMapped(police,coltypes);
                    headerAndColtypesDropSKP();

                    initialized = true;
                } else {
                    String[] temp_header = rows.next();
                    temp_header = Arrays.stream(temp_header)
                            .filter(h -> h != null && !h.trim().isEmpty())
                            .toArray(String[]::new);
                    boolean[] cols_kept = this.mapColnamesAndKeepNeededAux(mapping_col,temp_header);
                    temp_header = header_unify_aux(temp_header);
                    temp_header = headerKeep(temp_header,cols_kept);
                    if (!validateHeader(header,temp_header,file.getName())) {
                        headerIndexes = matchHeaders(header,temp_header);
                        for (int index : headerIndexes) {
                            if (index == -1) {
                                System.out.println("Error in matching headers" + police);
                                //throw new IOException();
                            }
                        }
                        i = 0;
                        while (rows.hasNext()) {
                            int j = 0; // real cols INDEX iter
                            int k = 0; // coltypes iter
                            String[] parsedRow = rows.next();
                            parsedRow = Arrays.copyOf(parsedRow, header.length);
                            for (String s : parsedRow) {
                                if (coltypes[k] != SKP && headerIndexes[j] != -1) {
                                    dfMapped.get(police).get(headerIndexes[j])[i] = get_lowercase_cell_of_type(s, coltypes[k], dateDefault);
                                    j++;
                                }
                                k++;
                            }
                            i++;
                        }
                        continue;
                    }
                }

                i = 0;
                while (rows.hasNext()) {
                    int j = 0; // real cols iter
                    int k = 0; // coltypes iter
                    String[] parsedRow = rows.next();
                    parsedRow = Arrays.copyOf(parsedRow, header.length);
                    for (String s : parsedRow) {
                        if (coltypes[k] != SKP) {
                            dfMapped.get(police).get(j)[i] = get_lowercase_cell_of_type(s, coltypes[k], dateDefault);
                            j++;
                        }
                        k++;
                    }
                    i++;
                }
            }
            date_autofill_agg_par_police(dfMapped.get(police));
        }
        populateUniqueStatutsDFMapped();
        computeMinMaxDatesForPoliciesMapped();
    }
    private int computeDimSIN() throws IOException {
        File[] files = new File(path).listFiles();
        if (files == null || files.length == 0) return 0;

        List<File> fileList = new ArrayList<>(Arrays.asList(files));

        int dim;
        String metadataCurrent = wd + "metadata/sin_" + pays + "_nb_lignes_" + CURRENT_MONTH + ".txt";
        if (new File(metadataCurrent).exists()) {
            return readDimFromMetadata(metadataCurrent);
        }

        dim = getDimFrom0_SIN(fileList, delim);
        writeDimToMetadata(metadataCurrent, dim);

        return dim;
    }
    private int getDimFrom0_SIN(List<File> fileList, char delim) throws IOException {
        int dim = 0;
        for (File file : fileList) {
            dim += csv_get_nrows(file.getPath(), delim);
        }
        return dim;
    }
    public void df_populateMapped (String police, Col_types[] vectypes) {
        for (Col_types coltype : vectypes) {
            switch (coltype) {
                case STR -> this.dfMapped.get(police).add(new String[nrowMapped.get(police)]);
                case DBL -> this.dfMapped.get(police).add(new Double[nrowMapped.get(police)]);
                case DAT -> this.dfMapped.get(police).add(new Date[nrowMapped.get(police)]);
                default -> {
                }
            }
        }
    }
    public void populateUniqueStatutsDFMapped() {
        for (String key : dfMapped.keySet()) {
            Object[] statuts = c("statut", dfMapped.get(key));
            for (Object obj : statuts) {
                uniqueStatuts.add((String) obj);
            }
        }
    }
    public void computeMinMaxDatesForPoliciesMapped() {
        // Initialize dictionaries with extreme Date values for each policy number and each statut
        for (String numPolice : uniqueNumPoliceValues) {
            minDateMap.put(numPolice, new HashMap<>());
            maxDateMap.put(numPolice, new HashMap<>());
            for (String statut : uniqueStatuts) {
                minDateMap.get(numPolice).put(statut, new Date(Long.MAX_VALUE));
                maxDateMap.get(numPolice).put(statut, new Date(Long.MIN_VALUE));
            }
        }

        // Initially set the overall min and max dates for each statut to extreme values
        for (String statut : uniqueStatuts) {
            overallMinDateByStatut.put(statut, new Date(Long.MAX_VALUE));
            overallMaxDateByStatut.put(statut, new Date(Long.MIN_VALUE));
        }

        for (String key : dfMapped.keySet()) {
            Date[] dates = (Date[]) this.c("date_surv", dfMapped.get(key));
            String[] statuts = (String[]) this.c("statut", dfMapped.get(key));

            for (int i = 0; i < dates.length; i++) {
                Date currentDate = dates[i];
                String currentStatut = statuts[i];

                // If current date is before the stored min date for the current policy and statut, update it
                if (currentDate.before(minDateMap.get(key).get(currentStatut))) {
                    minDateMap.get(key).put(currentStatut, currentDate);
                }

                // If current date is after the stored max date for the current policy and statut, update it
                if (currentDate.after(maxDateMap.get(key).get(currentStatut))) {
                    maxDateMap.get(key).put(currentStatut, currentDate);
                }

                // Update overall minimum date for current statut if necessary
                if (currentDate.before(overallMinDateByStatut.get(currentStatut))) {
                    overallMinDateByStatut.put(currentStatut, currentDate);
                }

                // Update overall maximum date for current statut if necessary
                if (currentDate.after(overallMaxDateByStatut.get(currentStatut))) {
                    overallMaxDateByStatut.put(currentStatut, currentDate);
                }
            }
        }
    }
    public String[] headerKeep(String[] header, boolean[] toKeep) {
        int count = 0;
        for (boolean keep : toKeep) {
            if (keep) {
                count++;
            }
        }

        String[] newHeader = new String[count];
        int j = 0;
        for (int i = 0; i < header.length; i++) {
            if (toKeep[i]) {
                newHeader[j] = header[i];
                j++;
            }
        }

        return newHeader;
    }

}
