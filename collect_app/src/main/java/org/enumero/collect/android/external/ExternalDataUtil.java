/*
 * Copyright (C) 2014 University of Washington
 *
 * Originally developed by Dobility, Inc. (as part of SurveyCTO)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.enumero.collect.android.external;

import org.javarosa.core.model.SelectChoice;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.form.api.FormEntryCaption;
import org.javarosa.form.api.FormEntryPrompt;
import org.javarosa.xpath.XPathParseTool;
import org.javarosa.xpath.expr.XPathExpression;
import org.javarosa.xpath.expr.XPathFuncExpr;
import org.javarosa.xpath.parser.XPathSyntaxException;
import org.enumero.collect.android.R;
import org.enumero.collect.android.application.Collect;
import org.enumero.collect.android.exception.ExternalDataException;
import org.enumero.collect.android.exception.InvalidSyntaxException;
import org.enumero.collect.android.external.handler.ExternalDataHandlerSearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Author: Meletis Margaritis
 * Date: 30/04/13
 * Time: 09:29
 */
public final class ExternalDataUtil {

    public static final String EXTERNAL_DATA_TABLE_NAME = "externalData";
    public static final String SORT_COLUMN_NAME = "c_sortby";

    private static final Pattern SEARCH_FUNCTION_REGEX = Pattern.compile("search\\(.+\\)");
    private static final String COLUMN_SEPARATOR = ",";
    private static final String FALLBACK_COLUMN_SEPARATOR = " ";
    public static final String JR_IMAGES_PREFIX = "jr://images/";

    public static String toSafeColumnName(String columnName, Map<String, String> cache) {
        String cachedName = cache.get(columnName);
        if (cachedName == null) {
            String safeColumnName = toSafeColumnName(columnName);
            cache.put(columnName, safeColumnName);
            return safeColumnName;
        } else {
            return cachedName;
        }
    }

    public static String toSafeColumnName(String columnName) {
        // SCTO-567 - begin all column names with "c_" to avoid possible conflicts with
        // reserved keywords; also, escape any potentially-illegal characters
        return "c_" + columnName.trim().replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(
                Locale.ENGLISH);
    }

    public static List<String> findMatchingColumnsAfterSafeningNames(String[] columnNames) {
        // key is the safe, value is the unsafe
        Map<String, String> map = new HashMap<String, String>();
        for (String columnName : columnNames) {
            if (columnName.trim().length() > 0) {
                String safeColumn = toSafeColumnName(columnName);
                if (!map.containsKey(safeColumn)) {
                    map.put(safeColumn, columnName);
                } else {
                    return Arrays.asList(map.get(safeColumn), columnName);
                }
            }
        }
        return null;
    }

    public static XPathFuncExpr getSearchXPathExpression(String appearance) {
        if (appearance == null) {
            appearance = "";
        }
        appearance = appearance.trim();

        Matcher matcher = SEARCH_FUNCTION_REGEX.matcher(appearance);
        if (matcher.find()) {
            String function = matcher.group(0);
            try {
                XPathExpression xpathExpression = XPathParseTool.parseXPath(function);
                if (XPathFuncExpr.class.isAssignableFrom(xpathExpression.getClass())) {
                    XPathFuncExpr xpathFuncExpr = (XPathFuncExpr) xpathExpression;
                    if (xpathFuncExpr.id.name.equalsIgnoreCase(
                            ExternalDataHandlerSearch.HANDLER_NAME)) {
                        // also check that the args are either 1, 4 or 6.
                        if (xpathFuncExpr.args.length == 1 || xpathFuncExpr.args.length == 4
                                || xpathFuncExpr.args.length == 6) {
                            return xpathFuncExpr;
                        } else {
                            throw new InvalidSyntaxException(Collect.getInstance().getString(
                                    R.string.ext_search_wrong_arguments_error));
                        }
                    } else {
                        // this might mean a problem in the regex above. Unit tests required.
                        throw new InvalidSyntaxException(Collect.getInstance().getString(
                                R.string.ext_search_wrong_function_error, xpathFuncExpr.id.name));
                    }
                } else {
                    // this might mean a problem in the regex above. Unit tests required.
                    throw new InvalidSyntaxException(
                            Collect.getInstance().getString(R.string.ext_search_bad_function_error,
                                    function));
                }
            } catch (XPathSyntaxException e) {
                throw new InvalidSyntaxException(
                        Collect.getInstance().getString(R.string.ext_search_generic_error,
                                appearance), e);
            }
        } else {
            return null;
        }
    }

    public static ArrayList<SelectChoice> populateExternalChoices(FormEntryPrompt formEntryPrompt,
            XPathFuncExpr xpathfuncexpr) {
        try {
            List<SelectChoice> selectChoices = formEntryPrompt.getSelectChoices();
            ArrayList<SelectChoice> returnedChoices = new ArrayList<SelectChoice>();
            for (SelectChoice selectChoice : selectChoices) {
                String value = selectChoice.getValue();
                if (isAnInteger(value)) {
                    // treat this as a static choice
                    returnedChoices.add(selectChoice);
                } else {
                    String displayColumns = formEntryPrompt.getSelectChoiceText(selectChoice);
                    String imageColumn = formEntryPrompt.getSpecialFormSelectChoiceText(
                            selectChoice, FormEntryCaption.TEXT_FORM_IMAGE);
                    if (imageColumn != null && imageColumn.startsWith(JR_IMAGES_PREFIX)) {
                        imageColumn = imageColumn.substring(JR_IMAGES_PREFIX.length());
                    }
                    //                    if (displayColumns == null || displayColumns.trim().length() == 0) {
                    //                        throw new InvalidSyntaxException("The label column in the choices sheet
                    // appears to be empty (or has been calculated as empty).");
                    //                    }

                    ExternalDataManager externalDataManager =
                            Collect.getInstance().getExternalDataManager();
                    FormInstance formInstance =
                            Collect.getInstance().getFormController().getFormDef().getInstance();
                    EvaluationContext baseEvaluationContext = new EvaluationContext(formInstance);
                    EvaluationContext evaluationContext = new EvaluationContext(
                            baseEvaluationContext, formEntryPrompt.getIndex().getReference());
                    // we can only add only the appropriate by querying the xPathFuncExpr.id.name
                    evaluationContext.addFunctionHandler(
                            new ExternalDataHandlerSearch(externalDataManager, displayColumns,
                                    value, imageColumn));

                    Object eval = xpathfuncexpr.eval(formInstance, evaluationContext);
                    if (eval.getClass().isAssignableFrom(ArrayList.class)) {
                        @SuppressWarnings("unchecked")
                        List<SelectChoice> dynamicChoices = (ArrayList<SelectChoice>) eval;
                        for (SelectChoice dynamicChoice : dynamicChoices) {
                            returnedChoices.add(dynamicChoice);
                        }
                    } else {
                        throw new ExternalDataException(
                                Collect.getInstance().getString(R.string.ext_search_return_error,
                                        eval.getClass().getName()));
                    }
                }
            }
            return returnedChoices;
        } catch (Exception e) {
            throw new ExternalDataException(e.getMessage(), e);
        }
    }

    /**
     * We could simple return new String(displayColumns + "," + valueColumn) but we want to handle
     * the cases
     * where the displayColumns (valueColumn) contain more commas than needed, in the middle, start
     * or end.
     *
     * @param valueColumn    single string to appear first.
     * @param displayColumns comma-separated string
     * @return A {@link java.util.LinkedHashMap} that contains the SQL columns as keys, and the CSV
     * columns as values
     */
    public static LinkedHashMap<String, String> createMapWithDisplayingColumns(String valueColumn,
            String displayColumns) {
        valueColumn = valueColumn.trim();

        LinkedHashMap<String, String> columns = new LinkedHashMap<String, String>();

        columns.put(toSafeColumnName(valueColumn), valueColumn);

        if (displayColumns != null && displayColumns.trim().length() > 0) {
            displayColumns = displayColumns.trim();

            List<String> commaSplitParts = splitTrimmed(displayColumns, COLUMN_SEPARATOR,
                    FALLBACK_COLUMN_SEPARATOR);

            for (String commaSplitPart : commaSplitParts) {
                columns.put(toSafeColumnName(commaSplitPart), commaSplitPart);
            }
        }

        return columns;
    }

    public static List<String> createListOfColumns(String columnString) {
        List<String> values = new ArrayList<String>();

        List<String> commaSplitParts = splitTrimmed(columnString, COLUMN_SEPARATOR,
                FALLBACK_COLUMN_SEPARATOR);

        for (String commaSplitPart : commaSplitParts) {
            values.add(toSafeColumnName(commaSplitPart));
        }

        return values;
    }

    protected static List<String> splitTrimmed(String displayColumns, String separator,
            String fallbackSeparator) {
        List<String> commaSplitParts = splitTrimmed(displayColumns, separator);

        // SCTO-584: Fall back to a space-separated list
        if (commaSplitParts.size() == 1 && displayColumns.contains(fallbackSeparator)) {
            commaSplitParts = splitTrimmed(displayColumns, fallbackSeparator);
        }
        return commaSplitParts;
    }

    protected static List<String> splitTrimmed(String text, String separator) {
        List<String> parts = new ArrayList<String>();
        StringTokenizer st = new StringTokenizer(text, separator);
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            if (token.length() > 0) {
                parts.add(token);
            }
        }
        return parts;
    }

    public static boolean containsAnyData(String[] row) {
        if (row == null || row.length == 0) {
            return false;
        }
        for (String value : row) {
            if (value != null && value.trim().length() > 0) {
                return true;
            }
        }
        return false;
    }

    public static String[] fillUpNullValues(String[] row, String[] headerRow) {
        String[] fullRow = new String[headerRow.length];

        for (int i = 0; i < fullRow.length; i++) {
            if (i < row.length) {
                String value = row[i];
                if (value == null) {
                    value = "";
                }
                fullRow[i] = value;
            } else {
                fullRow[i] = "";
            }
        }

        return fullRow;
    }

    public static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    public static boolean isAnInteger(String value) {
        if (value == null) {
            return false;
        }

        value = value.trim();

        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
