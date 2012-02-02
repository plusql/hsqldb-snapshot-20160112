package org.hsqldb;

import org.hsqldb.HsqlNameManager.SimpleName;
import org.hsqldb.ParserDQL.CompileContext;
import org.hsqldb.error.Error;
import org.hsqldb.error.ErrorCode;
import org.hsqldb.index.Index;
import org.hsqldb.lib.ArrayUtil;
import org.hsqldb.lib.HashMap;
import org.hsqldb.lib.HashMappedList;
import org.hsqldb.lib.HashSet;
import org.hsqldb.lib.HsqlArrayList;
import org.hsqldb.lib.HsqlList;
import org.hsqldb.lib.OrderedHashSet;
import org.hsqldb.lib.OrderedLongHashSet;
import org.hsqldb.navigator.RangeIterator;
import org.hsqldb.navigator.RowIterator;
import org.hsqldb.persist.PersistentStore;
import org.hsqldb.store.ValuePool;
import org.hsqldb.types.Type;

/**
 * Metadata for range variables, including conditions.
 *
 * @author Fred Toussi (fredt@users dot sourceforge.net)
 * @version 2.3.0
 * @since 1.9.0
 */
public class RangeVariable implements Cloneable {

    static final RangeVariable[] emptyArray = new RangeVariable[]{};

    //
    public static final int TABLE_RANGE      = 1;
    public static final int TRANSITION_RANGE = 2;
    public static final int PARAMETER_RANGE  = 3;
    public static final int VARIALBE_RANGE   = 4;

    //
    Table                  rangeTable;
    final SimpleName       tableAlias;
    private OrderedHashSet columnAliases;
    private SimpleName[]   columnAliasNames;
    private OrderedHashSet columnNames;
    OrderedHashSet         namedJoinColumns;
    HashMap                namedJoinColumnExpressions;
    private Object[]       emptyData;
    boolean[]              columnsInGroupBy;
    boolean                hasKeyedColumnInGroupBy;
    boolean[]              usedColumns;
    boolean[]              updatedColumns;

    //
    RangeVariableConditions[] joinConditions;
    RangeVariableConditions[] whereConditions;
    int                       subRangeCount;

    // non-index conditions
    Expression joinCondition;

    //
    boolean isLeftJoin;     // table joined with LEFT / FULL OUTER JOIN
    boolean isRightJoin;    // table joined with RIGHT / FULL OUTER JOIN
    boolean isBoundary;

    //
    int level;

    //
    int indexDistinctCount;

    //
    int rangePositionInJoin;

    //
    int rangePosition;

    //
    int parsePosition;

    // for variable and parameter lists
    HashMappedList variables;

    // variable, parameter, table
    int rangeType;

    //
    boolean isGenerated;

    public RangeVariable(HashMappedList variables, SimpleName rangeName,
                  boolean isVariable, int rangeType) {

        this.variables   = variables;
        this.rangeType   = rangeType;
        rangeTable       = null;
        tableAlias       = rangeName;
        emptyData        = null;
        columnsInGroupBy = null;
        usedColumns      = null;
        joinConditions = new RangeVariableConditions[]{
            new RangeVariableConditions(this, true) };
        whereConditions = new RangeVariableConditions[]{
            new RangeVariableConditions(this, false) };
    }

    public RangeVariable(Table table, SimpleName alias, OrderedHashSet columnList,
                  SimpleName[] columnNameList, CompileContext compileContext) {

        rangeType        = TABLE_RANGE;
        rangeTable       = table;
        tableAlias       = alias;
        columnAliases    = columnList;
        columnAliasNames = columnNameList;
        joinConditions = new RangeVariableConditions[]{
            new RangeVariableConditions(this, true) };
        whereConditions = new RangeVariableConditions[]{
            new RangeVariableConditions(this, false) };

        compileContext.registerRangeVariable(this);

        SubQuery subQuery = rangeTable.getSubQuery();

        if (subQuery == null || subQuery.isResolved()) {
            setRangeTableVariables();
        }
    }

    public RangeVariable(Table table, int position) {

        rangeType        = TABLE_RANGE;
        rangeTable       = table;
        tableAlias       = null;
        emptyData        = rangeTable.getEmptyRowData();
        columnsInGroupBy = rangeTable.getNewColumnCheckList();
        usedColumns      = rangeTable.getNewColumnCheckList();
        rangePosition    = position;
        joinConditions = new RangeVariableConditions[]{
            new RangeVariableConditions(this, true) };
        whereConditions = new RangeVariableConditions[]{
            new RangeVariableConditions(this, false) };
    }

    public void resetViewRageTableAsSubquery() {
        rangeTable                   = ((View) rangeTable).getSubqueryTable();
        joinConditions[0].rangeIndex = rangeTable.getPrimaryIndex();
    }

    public void setRangeTableVariables() {

        if (columnAliasNames != null
                && rangeTable.getColumnCount() != columnAliasNames.length) {
            throw Error.error(ErrorCode.X_42593);
        }

        emptyData                    = rangeTable.getEmptyRowData();
        columnsInGroupBy             = rangeTable.getNewColumnCheckList();
        usedColumns                  = rangeTable.getNewColumnCheckList();
        joinConditions[0].rangeIndex = rangeTable.getPrimaryIndex();
    }

    public RangeVariable duplicate() {

        RangeVariable r = null;

        try {
            r = (RangeVariable) super.clone();
        } catch (CloneNotSupportedException ex) {
            throw Error.runtimeError(ErrorCode.U_S0500, "RangeVariable");
        }

        r.resetConditions();

        return r;
    }

    public void setJoinType(boolean isLeft, boolean isRight) {

        isLeftJoin  = isLeft;
        isRightJoin = isRight;

        if (isRightJoin) {
            whereConditions[0].rangeIndex = rangeTable.getPrimaryIndex();
        }
    }

    public void addNamedJoinColumns(OrderedHashSet columns) {
        namedJoinColumns = columns;
    }

    public void addColumn(int columnIndex) {
        usedColumns[columnIndex] = true;
    }

    public void addAllColumns() {}

    public void addNamedJoinColumnExpression(String name, Expression e) {

        if (namedJoinColumnExpressions == null) {
            namedJoinColumnExpressions = new HashMap();
        }

        namedJoinColumnExpressions.put(name, e);
    }

    public ExpressionColumn getColumnExpression(String name) {

        return namedJoinColumnExpressions == null ? null
                                                  : (ExpressionColumn) namedJoinColumnExpressions
                                                  .get(name);
    }

    public Table getTable() {
        return rangeTable;
    }

    public boolean hasAnyIndexCondition() {

        for (int i = 0; i < joinConditions.length; i++) {
            if (joinConditions[0].indexedColumnCount > 0) {
                return true;
            }
        }

        for (int i = 0; i < whereConditions.length; i++) {
            if (whereConditions[0].indexedColumnCount > 0) {
                return true;
            }
        }

        return false;
    }

    public boolean hasSingleIndexCondition() {
        return joinConditions.length == 1
               && joinConditions[0].indexedColumnCount > 0;
    }

    public boolean setDistinctColumnsOnIndex(int[] colMap) {

        if (joinConditions.length != 1) {
            return false;
        }

        int[] indexColMap = joinConditions[0].rangeIndex.getColumns();

        if (colMap.length != ArrayUtil.countTrueElements(usedColumns)) {
            return false;
        }

        if (colMap.length == 1 && colMap[0] == indexColMap[0]) {
            indexDistinctCount = 1;

            return true;
        }

        return false;
    }

    /**
     * Used for sort
     */
    public Index getSortIndex() {

        if (joinConditions.length == 1) {
            return joinConditions[0].rangeIndex;
        } else {
            return null;
        }
    }

    /**
     * Used for sort
     */
    public boolean setSortIndex(Index index, boolean reversed) {

        if (joinConditions.length == 1) {
            if (joinConditions[0].indexedColumnCount == 0) {
                joinConditions[0].rangeIndex = index;
                joinConditions[0].reversed   = reversed;

                return true;
            }
        }

        return false;
    }

    public boolean reverseOrder() {

        joinConditions[0].reverseIndexCondition();

        return true;
    }

    public OrderedHashSet getColumnNames() {

        if (columnNames == null) {
            columnNames = new OrderedHashSet();

            rangeTable.getColumnNames(this.usedColumns, columnNames);
        }

        return columnNames;
    }

    public OrderedHashSet getUniqueColumnNameSet() {

        OrderedHashSet set = new OrderedHashSet();

        if (columnAliases != null) {
            set.addAll(columnAliases);

            return set;
        }

        for (int i = 0; i < rangeTable.columnList.size(); i++) {
            String  name  = rangeTable.getColumn(i).getName().name;
            boolean added = set.add(name);

            if (!added) {
                throw Error.error(ErrorCode.X_42578, name);
            }
        }

        return set;
    }

    public int findColumn(String schemaName, String tableName,
                          String columnName) {

        if (resolvesSchemaAndTableName(schemaName, tableName)) {
            return findColumn(columnName);
        }

        return -1;
    }

    /**
     * Retruns index for column
     *
     * @param columnName name of column
     * @return int index or -1 if not found
     */
    private int findColumn(String columnName) {

        if (namedJoinColumnExpressions != null
                && namedJoinColumnExpressions.containsKey(columnName)) {
            return -1;
        }

        if (variables != null) {
            return variables.getIndex(columnName);
        } else if (columnAliases != null) {
            return columnAliases.getIndex(columnName);
        } else {
            return rangeTable.findColumn(columnName);
        }
    }

    public ColumnSchema getColumn(int i) {

        if (variables == null) {
            return rangeTable.getColumn(i);
        } else {
            return (ColumnSchema) variables.get(i);
        }
    }

    public SimpleName getColumnAlias(int i) {

        if (columnAliases == null) {
            return rangeTable.getColumn(i).getName();
        } else {
            return columnAliasNames[i];
        }
    }

    public boolean hasColumnAlias() {
        return columnAliases != null;
    }

    public SimpleName getTableAlias() {
        return tableAlias == null ? rangeTable.getName()
                                  : tableAlias;
    }

    public RangeVariable getRangeForTableName(String name) {

        if (resolvesTableName(name)) {
            return this;
        }

        return null;
            }

    private boolean resolvesSchemaAndTableName(String schemaName,
            String tableName) {
        return resolvesSchemaName(schemaName) && resolvesTableName(tableName);
    }

    private boolean resolvesTableName(String name) {

        if (name == null) {
            return true;
        }

        if (variables != null) {
            if (tableAlias != null) {
                return name.equals(tableAlias.name);
            }

            return false;
        }

        if (tableAlias == null) {
            if (name.equals(rangeTable.getName().name)) {
                return true;
            }
        } else if (name.equals(tableAlias.name)) {
            return true;
        }

        return false;
    }

    private boolean resolvesSchemaName(String name) {

        if (name == null) {
            return true;
        }

        if (variables != null) {
            return false;
        }

        if (tableAlias != null) {
            return false;
        }

        return name.equals(rangeTable.getSchemaName().name);
    }

    /**
     * Add all columns to a list of expressions
     */
    public void addTableColumns(HsqlArrayList exprList) {

        if (namedJoinColumns != null) {
            int count    = exprList.size();
            int position = 0;

            for (int i = 0; i < count; i++) {
                Expression e          = (Expression) exprList.get(i);
                String     columnName = e.getColumnName();

                if (namedJoinColumns.contains(columnName)) {
                    if (position != i) {
                        exprList.remove(i);
                        exprList.add(position, e);
                    }

                    e = getColumnExpression(columnName);

                    exprList.set(position, e);

                    position++;
                }
            }
        }

        addTableColumns(exprList, exprList.size(), namedJoinColumns);
    }

    /**
     * Add all columns to a list of expressions
     */
    public int addTableColumns(HsqlArrayList exprList, int position,
                        HashSet exclude) {

        Table table = getTable();
        int   count = table.getColumnCount();

        for (int i = 0; i < count; i++) {
            ColumnSchema column = table.getColumn(i);
            String columnName = columnAliases == null ? column.getName().name
                                                      : (String) columnAliases
                                                          .get(i);

            if (exclude != null && exclude.contains(columnName)) {
                continue;
            }

            Expression e = new ExpressionColumn(this, i);

            exprList.add(position++, e);
        }

        return position;
    }

    public void addTableColumns(RangeVariable subRange, Expression expression,
                                HashSet exclude) {

        if (subRange == this) {
        Table         table = getTable();
        int           count = table.getColumnCount();

            addTableColumns(expression, 0, count, exclude);
        }
    }

    protected int getFirstColumnIndex(RangeVariable subRange) {

        if (subRange == this) {
            return 0;
        }

        return -1;
    }

    protected void addTableColumns(Expression expression, int start, int count,
                         HashSet exclude) {

        Table         table = getTable();
        HsqlArrayList list  = new HsqlArrayList();

        for (int i = start; i < start + count; i++) {
            ColumnSchema column = table.getColumn(i);
            String columnName = columnAliases == null ? column.getName().name
                                                      : (String) columnAliases
                                                          .get(i);

            if (exclude != null && exclude.contains(columnName)) {
                continue;
            }

            Expression e = new ExpressionColumn(this, i);

            list.add(e);
        }

        Expression[] nodes = new Expression[list.size()];

        list.toArray(nodes);

        expression.nodes = nodes;
    }

    /**
     * Removes reference to Index to avoid possible memory leaks after alter
     * table or drop index
     */
    public void setForCheckConstraint() {
        joinConditions[0].rangeIndex = null;
        rangePosition                = 0;
    }

    /**
     * used before condition processing
     */
    public Expression getJoinCondition() {
        return joinCondition;
    }

    public void addJoinCondition(Expression e) {
        joinCondition = ExpressionLogical.andExpressions(joinCondition, e);
    }

    public void resetConditions() {

        Index index = joinConditions[0].rangeIndex;

        joinConditions = new RangeVariableConditions[]{
            new RangeVariableConditions(this, true) };
        joinConditions[0].rangeIndex = index;
        whereConditions = new RangeVariableConditions[]{
            new RangeVariableConditions(this, false) };
    }

    public OrderedHashSet getSubqueries() {

        OrderedHashSet set = null;

        if (joinCondition != null) {
            set = joinCondition.collectAllSubqueries(set);
        }

        if (rangeTable instanceof TableDerived) {
            QueryExpression baseQueryExpression =
                ((TableDerived) rangeTable).getQueryExpression();

            if (((TableDerived) rangeTable).view != null) {
                if (set == null) {
                    set = new OrderedHashSet();
                }

                set.addAll(((TableDerived) rangeTable).view.getSubqueries());
            } else if (baseQueryExpression == null) {
                SubQuery sq = rangeTable.getSubQuery();

                if (sq != null) {
                    set = OrderedHashSet.add(set, sq);

                    if (sq.dataExpression != null) {
                        OrderedHashSet.addAll(
                            set, sq.dataExpression.getSubqueries());
                    }
                }
            } else {
                OrderedHashSet temp = baseQueryExpression.getSubqueries();

                set = OrderedHashSet.addAll(set, temp);
                set = OrderedHashSet.add(set, rangeTable.getSubQuery());
            }
        }

        return set;
    }

    public void replaceColumnReference(RangeVariable range,
                                       Expression[] list) {

        if (joinCondition != null) {
            joinCondition.replaceColumnReferences(range, list);
        }
    }

    public void replaceRangeVariables(RangeVariable[] ranges,
                                      RangeVariable[] newRanges) {

        if (joinCondition != null) {
            joinCondition.replaceRangeVariables(ranges, newRanges);
        }
    }

    public void resolveRangeTable(Session session,
                                  RangeVariable[] rangeVariables,
                                  int rangeCount,
                                  RangeVariable[] outerRanges) {

        Table    table    = rangeTable;
        SubQuery subQuery = table.getSubQuery();

        if (subQuery != null && !subQuery.isResolved()) {
            if (subQuery.dataExpression != null) {
                HsqlList unresolved =
                    subQuery.dataExpression.resolveColumnReferences(session,
                        RangeVariable.emptyArray, null);

                if (unresolved != null) {
                    unresolved =
                        subQuery.dataExpression.resolveColumnReferences(
                            session, rangeVariables, rangeCount, null, true);
                }

                if (unresolved != null) {
                    unresolved =
                        subQuery.dataExpression.resolveColumnReferences(
                            session, outerRanges, null);
                }

                if (unresolved != null) {
                    throw Error.error(
                        ErrorCode.X_42501,
                        ((Expression) unresolved.get(0)).getSQL());
                }

                subQuery.dataExpression.resolveTypes(session, null);
                setRangeTableVariables();
            }

            if (subQuery.queryExpression != null) {
                subQuery.queryExpression.resolveReferences(session,
                        outerRanges);

                HsqlList list =
                    subQuery.queryExpression.getUnresolvedExpressions();

                // todo resolve against i ranges
                HsqlList unresolved = Expression.resolveColumnSet(session,
                    rangeVariables, rangeCount, list, null);

                if (unresolved != null) {
                    throw Error.error(
                        ErrorCode.X_42501,
                        ((Expression) unresolved.get(0)).getSQL());
                }

                subQuery.queryExpression.resolveTypes(session);
                subQuery.prepareTable(session);
                subQuery.setCorrelated();
                setRangeTableVariables();
            }
        }
    }

    /**
     * Retreives a String representation of this obejct. <p>
     *
     * The returned String describes this object's table, alias
     * access mode, index, join mode, Start, End and And conditions.
     *
     * @return a String representation of this object
     */
    public String describe(Session session, int blanks) {

        StringBuffer sb;
        String       b = ValuePool.spaceString.substring(0, blanks);

        sb = new StringBuffer();

        String temp = "INNER";

        if (isLeftJoin) {
            temp = "LEFT OUTER";

            if (isRightJoin) {
                temp = "FULL";
            }
        } else if (isRightJoin) {
            temp = "RIGHT OUTER";
        }

        sb.append(b).append("join type=").append(temp).append("\n");
        sb.append(b).append("table=").append(rangeTable.getName().name).append(
            "\n");

        if (tableAlias != null) {
            sb.append(b).append("alias=").append(tableAlias.name).append("\n");
        }

        RangeVariableConditions[] conditions = joinConditions;

        if (whereConditions[0].hasIndexCondition()) {
            conditions = whereConditions;
        }

        boolean fullScan = !conditions[0].hasIndexCondition();

        sb.append(b);

        if (conditions == whereConditions) {
            if (joinConditions[0].nonIndexCondition != null) {
                sb.append("join condition = [");
                sb.append(joinConditions[0].nonIndexCondition.describe(session,
                        blanks));
                sb.append(b).append("]\n");
                sb.append(b);
            }
        }

        sb.append("access=").append(fullScan ? "FULL SCAN"
                                             : "INDEX PRED").append("\n");

        for (int i = 0; i < conditions.length; i++) {
            if (i > 0) {
                sb.append(b).append("OR condition = [");
            } else {
                sb.append(b);

                if (conditions == whereConditions) {
                    sb.append("where condition = [");
                } else {
                    sb.append("join condition = [");
                }
            }

            sb.append(conditions[i].describe(session, blanks + 2));
            sb.append(b).append("]\n");
        }

        if (conditions == joinConditions) {
            sb.append(b);

            if (whereConditions[0].nonIndexCondition != null) {
                sb.append("where condition = [");
                sb.append(
                    whereConditions[0].nonIndexCondition.describe(
                        session, blanks));
                sb.append(b).append("]\n");
                sb.append(b);
            }
        }

        return sb.toString();
    }

    public RangeIteratorMain getIterator(Session session) {

        RangeIteratorMain it;

        if (this.isRightJoin) {
            it = new RangeIteratorRight(session, this, null);
        } else {
            it = new RangeIteratorMain(session, this);
        }

        session.sessionContext.setRangeIterator(it);

        return it;
    }

    public static RangeIterator getIterator(Session session,
            RangeVariable[] rangeVars) {

        if (rangeVars.length == 1) {
            return rangeVars[0].getIterator(session);
        }

        RangeIteratorMain[] iterators =
            new RangeIteratorMain[rangeVars.length];

        for (int i = 0; i < rangeVars.length; i++) {
            iterators[i] = rangeVars[i].getIterator(session);
        }

        return new RangeIteratorJoined(iterators);
    }

    public static class RangeIteratorBase implements RangeIterator {

        Session         session;
        int             rangePosition;
        RowIterator     it;
        PersistentStore store;
        Object[]        currentData;
        Row             currentRow;
        boolean         isBeforeFirst;
        RangeVariable   rangeVar;

        private RangeIteratorBase() {}

        public boolean isBeforeFirst() {
            return isBeforeFirst;
        }

        public boolean next() {

            if (isBeforeFirst) {
                isBeforeFirst = false;
            } else {
                if (it == null) {
                    return false;
                }
            }

            currentRow = it.getNextRow();

            if (currentRow == null) {
                return false;
            } else {
                currentData = currentRow.getData();

                return true;
            }
        }

        public Row getCurrentRow() {
            return currentRow;
        }

        public Object[] getCurrent() {
            return currentData;
        }

        public Object getCurrent(int i) {
            return currentData == null ? null
                                       : currentData[i];
        }

        public void setCurrent(Object[] data) {
            currentData = data;
        }

        public long getRowId() {

            return currentRow == null ? 0
                                      : ((long) rangeVar.rangeTable.getId() << 32)
                                        + ((long) currentRow.getPos());
        }

        public Object getRowidObject() {
            return currentRow == null ? null
                                      : ValuePool.getLong(getRowId());
        }

        public void remove() {}

        public void reset() {

            if (it != null) {
                it.release();
            }

            it            = null;
            currentRow    = null;
            isBeforeFirst = true;
        }

        public int getRangePosition() {
            return rangePosition;
        }

        public Row getNextRow() {
            throw Error.runtimeError(ErrorCode.U_S0500, "RangeVariable");
        }

        public boolean hasNext() {
            throw Error.runtimeError(ErrorCode.U_S0500, "RangeVariable");
        }

        public Object[] getNext() {
            throw Error.runtimeError(ErrorCode.U_S0500, "RangeVariable");
        }

        public boolean setRowColumns(boolean[] columns) {
            throw Error.runtimeError(ErrorCode.U_S0500, "RangeVariable");
        }

        public void release() {

            if (it != null) {
                it.release();
            }
        }
    }

    public static class RangeIteratorMain extends RangeIteratorBase {

        boolean                   hasLeftOuterRow;
        boolean                   isFullIterator;
        RangeVariableConditions[] conditions;
        RangeVariableConditions[] whereConditions;
        RangeVariableConditions[] joinConditions;
        int                       condIndex = 0;

        //
        OrderedLongHashSet lookup;

        //
        Object[] currentJoinData = null;

        RangeIteratorMain() {
            super();
        }

        private RangeIteratorMain(Session session, RangeVariable rangeVar) {

            this.rangePosition = rangeVar.rangePosition;
            this.store         = rangeVar.rangeTable.getRowStore(session);
            this.session       = session;
            this.rangeVar      = rangeVar;
            currentData        = rangeVar.emptyData;
            isBeforeFirst      = true;
            whereConditions    = rangeVar.whereConditions;
            joinConditions     = rangeVar.joinConditions;

            if (rangeVar.isRightJoin) {
                lookup = new OrderedLongHashSet();
            }

            conditions = rangeVar.joinConditions;

            if (rangeVar.whereConditions[0].hasIndexCondition()) {
                conditions = rangeVar.whereConditions;
            }
        }

        public boolean isBeforeFirst() {
            return isBeforeFirst;
        }

        public boolean next() {

            while (condIndex < conditions.length) {
                if (isBeforeFirst) {
                    isBeforeFirst = false;

                    initialiseIterator();
                }

                boolean result = findNext();

                if (result) {
                    return true;
                }

                reset();

                condIndex++;
            }

            condIndex = 0;

            return false;
        }

        public void remove() {}

        public void reset() {

            if (it != null) {
                it.release();
            }

            it            = null;
            currentData   = rangeVar.emptyData;
            currentRow    = null;
            isBeforeFirst = true;
        }

        public int getRangePosition() {
            return rangeVar.rangePosition;
        }

        /**
         */
        protected void initialiseIterator() {

            if (condIndex == 0) {
                hasLeftOuterRow = rangeVar.isLeftJoin;
            }

            if (conditions[condIndex].isFalse) {
                it = conditions[condIndex].rangeIndex.emptyIterator();

                return;
            }

            SubQuery subQuery = rangeVar.rangeTable.getSubQuery();

            if (subQuery != null && subQuery.isCorrelated()) {
                subQuery.materialiseCorrelated(session);
            }

            if (conditions[condIndex].indexCond == null) {
                if (conditions[condIndex].reversed) {
                    it = conditions[condIndex].rangeIndex.lastRow(session,
                            store);
                } else {
                    it = conditions[condIndex].rangeIndex.firstRow(session,
                            store);
                }
            } else {
                getFirstRow();

                if (!conditions[condIndex].isJoin) {
                    hasLeftOuterRow = false;
                }
            }
        }

        private void getFirstRow() {

            if (currentJoinData == null
                    || currentJoinData.length
                       < conditions[condIndex].indexedColumnCount) {
                currentJoinData =
                    new Object[conditions[condIndex].indexedColumnCount];
            }

            for (int i = 0; i < conditions[condIndex].indexedColumnCount;
                    i++) {
                int range = 0;
                int opType = i == conditions[condIndex].indexedColumnCount - 1
                             ? conditions[condIndex].opType
                             : conditions[condIndex].indexCond[i].getType();

                if (opType == OpTypes.IS_NULL || opType == OpTypes.NOT
                        || opType == OpTypes.MAX) {
                    currentJoinData[i] = null;

                    continue;
                }

                Type valueType =
                    conditions[condIndex].indexCond[i].getRightNode()
                        .getDataType();
                Object value =
                    conditions[condIndex].indexCond[i].getRightNode().getValue(
                        session);
                Type targetType =
                    conditions[condIndex].indexCond[i].getLeftNode()
                        .getDataType();

                if (targetType != valueType) {
                    range = targetType.compareToTypeRange(value);

                    if (range == 0) {
                        if (targetType.typeComparisonGroup
                                != valueType.typeComparisonGroup) {
                            value = targetType.convertToType(session, value,
                                                             valueType);
                        }
                    }
                }

                if (i == 0) {
                    int exprType =
                        conditions[condIndex].indexCond[0].getType();

                    if (range < 0) {
                        switch (exprType) {

                            case OpTypes.GREATER_EQUAL :
                            case OpTypes.GREATER :
                                value = null;
                                break;

                            default :
                                it = conditions[condIndex].rangeIndex
                                    .emptyIterator();

                                return;
                        }
                    } else if (range > 0) {
                        switch (exprType) {

                            case OpTypes.NOT :
                                value = null;
                                break;

                            default :
                                it = conditions[condIndex].rangeIndex
                                    .emptyIterator();

                                return;
                        }
                    }
                }

                currentJoinData[i] = value;
            }

            it = conditions[condIndex].rangeIndex.findFirstRow(session, store,
                    currentJoinData, conditions[condIndex].indexedColumnCount,
                    rangeVar.indexDistinctCount, conditions[condIndex].opType,
                    conditions[condIndex].reversed, null);
        }

        /**
         * Advances to the next available value. <p>
         *
         * @return true if a next value is available upon exit
         */
        private boolean findNext() {

            boolean result = false;

            while (true) {
                currentRow = it.getNextRow();

                if (currentRow == null) {
                    break;
                }

                currentData = currentRow.getData();

                if (conditions[condIndex].terminalCondition != null
                        && !conditions[condIndex].terminalCondition
                            .testCondition(session)) {
                    break;
                }

                if (conditions[condIndex].indexEndCondition != null
                        && !conditions[condIndex].indexEndCondition
                            .testCondition(session)) {
                    if (!conditions[condIndex].isJoin) {
                        hasLeftOuterRow = false;
                    }

                    break;
                }

                if (joinConditions[condIndex].nonIndexCondition != null
                        && !joinConditions[condIndex].nonIndexCondition
                            .testCondition(session)) {
                    continue;
                }

                if (whereConditions[condIndex].nonIndexCondition != null
                        && !whereConditions[condIndex].nonIndexCondition
                            .testCondition(session)) {
                    hasLeftOuterRow = false;

                    addFoundRow();

                    continue;
                }

                Expression e = conditions[condIndex].excludeConditions;

                if (e != null && e.testCondition(session)) {
                    continue;
                }

                addFoundRow();

                hasLeftOuterRow = false;

                return true;
            }

            it.release();

            currentRow  = null;
            currentData = rangeVar.emptyData;

            if (hasLeftOuterRow && condIndex == conditions.length - 1) {
                result =
                    (whereConditions[condIndex].nonIndexCondition == null
                     || whereConditions[condIndex].nonIndexCondition
                         .testCondition(session));
                hasLeftOuterRow = false;
            }

            return result;
        }

        private void addFoundRow() {

            if (rangeVar.isRightJoin) {
                lookup.add(currentRow.getPos());
            }
        }
    }

    public static class RangeIteratorRight extends RangeIteratorMain {

        private RangeIteratorRight(Session session, RangeVariable rangeVar,
                                   RangeIteratorMain main) {

            super(session, rangeVar);

            isFullIterator = true;
        }

        boolean isOnRightOuterRows;

        public void setOnOuterRows() {

            conditions         = rangeVar.whereConditions;
            isOnRightOuterRows = true;
            hasLeftOuterRow    = false;
            condIndex          = 0;

            initialiseIterator();
        }

        public boolean next() {

            if (isOnRightOuterRows) {
                if (it == null) {
                    return false;
                }

                return findNextRight();
            } else {
                return super.next();
            }
        }

        private boolean findNextRight() {

            boolean result = false;

            while (true) {
                currentRow = it.getNextRow();

                if (currentRow == null) {
                    break;
                }

                currentData = currentRow.getData();

                if (conditions[condIndex].indexEndCondition != null
                        && !conditions[condIndex].indexEndCondition
                            .testCondition(session)) {
                    break;
                }

                if (conditions[condIndex].nonIndexCondition != null
                        && !conditions[condIndex].nonIndexCondition
                            .testCondition(session)) {
                    continue;
                }

                if (!lookupAndTest()) {
                    continue;
                }

                result = true;

                break;
            }

            if (result) {
                return true;
            }

            it.release();

            currentRow  = null;
            currentData = rangeVar.emptyData;

            return result;
        }

        private boolean lookupAndTest() {

            boolean result = !lookup.contains(currentRow.getPos());

            if (result) {
                currentData = currentRow.getData();

                if (conditions[condIndex].nonIndexCondition != null
                        && !conditions[condIndex].nonIndexCondition
                            .testCondition(session)) {
                    result = false;
                }
            }

            return result;
        }
    }

    public static class RangeIteratorJoined extends RangeIteratorBase {

        RangeIteratorMain[] rangeIterators;
        int                 currentIndex = 0;

        public RangeIteratorJoined(RangeIteratorMain[] rangeIterators) {
            this.rangeIterators = rangeIterators;
            isBeforeFirst       = true;
        }

        public boolean isBeforeFirst() {
            return isBeforeFirst;
        }

        public boolean next() {

            while (currentIndex >= 0) {
                RangeIteratorMain it = rangeIterators[currentIndex];

                if (it.next()) {
                    if (currentIndex < rangeIterators.length - 1) {
                        currentIndex++;

                        continue;
                    }

                    currentRow  = rangeIterators[currentIndex].currentRow;
                    currentData = currentRow.getData();

                    return true;
                } else {
                    it.reset();

                    currentIndex--;

                    continue;
                }
            }

            currentData =
                rangeIterators[rangeIterators.length - 1].rangeVar.emptyData;
            currentRow = null;

            for (int i = 0; i < rangeIterators.length; i++) {
                rangeIterators[i].reset();
            }

            return false;
        }

        public void remove() {}

        public void release() {

            if (it != null) {
                it.release();
            }

            for (int i = 0; i < rangeIterators.length; i++) {
                rangeIterators[i].reset();
            }
        }

        public void reset() {

            super.reset();

            for (int i = 0; i < rangeIterators.length; i++) {
                rangeIterators[i].reset();
            }
        }

        public int getRangePosition() {
            return 0;
        }
    }

    public static class RangeVariableConditions {

        final RangeVariable rangeVar;
        Expression[]        indexCond;
        Expression[]        indexEndCond;
        int[]               opTypes;
        int[]               opTypesEnd;
        Expression          indexEndCondition;
        int                 indexedColumnCount;
        Index               rangeIndex;
        final boolean       isJoin;
        Expression          excludeConditions;
        Expression          nonIndexCondition;
        Expression          terminalCondition;
        int                 opType;
        int                 opTypeEnd;
        boolean             isFalse;
        boolean             reversed;
        boolean             hasIndex;

        RangeVariableConditions(RangeVariable rangeVar, boolean isJoin) {
            this.rangeVar = rangeVar;
            this.isJoin   = isJoin;
        }

        RangeVariableConditions(RangeVariableConditions base) {

            this.rangeVar     = base.rangeVar;
            this.isJoin       = base.isJoin;
            nonIndexCondition = base.nonIndexCondition;
        }

        boolean hasIndexCondition() {
            return indexedColumnCount > 0;
        }

        boolean hasIndex() {
            return hasIndex;
        }

        void addCondition(Expression e) {

            if (e == null) {
                return;
            }

            if (e instanceof ExpressionLogical) {
                if (((ExpressionLogical) e).isTerminal) {
                    terminalCondition = e;
                }
            }

            nonIndexCondition =
                ExpressionLogical.andExpressions(nonIndexCondition, e);

            if (Expression.EXPR_FALSE.equals(nonIndexCondition)) {
                isFalse = true;
            }

            if (rangeIndex == null || rangeIndex.getColumnCount() == 0) {
                return;
            }

            if (indexedColumnCount == 0) {
                return;
            }

            if (e.getIndexableExpression(rangeVar) == null) {
                return;
            }

            int   colIndex  = e.getLeftNode().getColumnIndex();
            int[] indexCols = rangeIndex.getColumns();

            switch (e.getType()) {

                case OpTypes.GREATER :
                case OpTypes.GREATER_EQUAL : {

                    // replaces existing condition
                    if (opType == OpTypes.NOT) {
                        if (indexCols[indexedColumnCount - 1] == colIndex) {
                            nonIndexCondition =
                                ExpressionLogical.andExpressions(
                                    nonIndexCondition,
                                    indexCond[indexedColumnCount - 1]);
                            indexCond[indexedColumnCount - 1] = e;
                            opType                            = e.opType;
                            opTypes[indexedColumnCount - 1]   = e.opType;

                            if (e.exprSubType == OpTypes.LIKE
                                    && indexedColumnCount == 1) {
                                indexEndCond[indexedColumnCount - 1] =
                                    ExpressionLogical.andExpressions(
                                        indexEndCond[indexedColumnCount - 1],
                                        e.nodes[2]);
                            }
                        }
                    } else {
                        addToIndexConditions(e);
                    }

                    break;
                }
                case OpTypes.SMALLER :
                case OpTypes.SMALLER_EQUAL : {
                    if (opType == OpTypes.GREATER
                            || opType == OpTypes.GREATER_EQUAL
                            || opType == OpTypes.NOT) {
                        if (opTypeEnd != OpTypes.MAX) {
                            break;
                        }

                        if (indexCols[indexedColumnCount - 1] == colIndex) {
                            indexEndCond[indexedColumnCount - 1] = e;
                            indexEndCondition =
                                ExpressionLogical.andExpressions(
                                    indexEndCondition, e);
                            opTypeEnd                          = e.opType;
                            opTypesEnd[indexedColumnCount - 1] = e.opType;
                        }
                    } else {
                        addToIndexEndConditions(e);
                    }

                    break;
                }
                default :
            }
        }

        private boolean addToIndexConditions(Expression e) {

            if (opType == OpTypes.EQUAL || opType == OpTypes.IS_NULL) {
                if (indexedColumnCount < rangeIndex.getColumnCount()) {
                    if (rangeIndex.getColumns()[indexedColumnCount]
                            == e.getLeftNode().getColumnIndex()) {
                        indexCond[indexedColumnCount]  = e;
                        opType                         = e.opType;
                        opTypes[indexedColumnCount]    = e.opType;
                        opTypeEnd                      = OpTypes.MAX;
                        opTypesEnd[indexedColumnCount] = OpTypes.MAX;

                        indexedColumnCount++;

                        return true;
                    }
                }
            }

            return false;
        }

        private boolean addToIndexEndConditions(Expression e) {

            if (opType == OpTypes.EQUAL || opType == OpTypes.IS_NULL) {
                if (indexedColumnCount < rangeIndex.getColumnCount()) {
                    if (rangeIndex.getColumns()[indexedColumnCount]
                            == e.getLeftNode().getColumnIndex()) {
                        Expression condition =
                            ExpressionLogical.newNotNullCondition(
                                e.getLeftNode());

                        indexCond[indexedColumnCount]    = condition;
                        indexEndCond[indexedColumnCount] = e;
                        indexEndCondition =
                            ExpressionLogical.andExpressions(indexEndCondition,
                                                             e);
                        opType                         = OpTypes.NOT;
                        opTypes[indexedColumnCount]    = OpTypes.NOT;
                        opTypeEnd                      = e.opType;
                        opTypesEnd[indexedColumnCount] = e.opType;

                        indexedColumnCount++;

                        return true;
                    }
                }
            }

            return false;
        }

        /**
         *
         * @param exprList list of expressions
         * @param index Index to use
         * @param colCount number of columns searched
         */
        public void addIndexCondition(Expression[] exprList, Index index,
                               int colCount) {

            int indexColCount = index.getColumnCount();

            rangeIndex   = index;
            indexCond    = new Expression[indexColCount];
            indexEndCond = new Expression[indexColCount];
            opTypes      = new int[indexColCount];
            opTypesEnd   = new int[indexColCount];
            opType       = exprList[0].opType;
            opTypes[0]   = exprList[0].opType;

            switch (opType) {

                case OpTypes.NOT :
                    indexCond     = exprList;
                    opTypeEnd     = OpTypes.MAX;
                    opTypesEnd[0] = OpTypes.MAX;
                    break;

                case OpTypes.GREATER :
                case OpTypes.GREATER_EQUAL :
                    indexCond = exprList;

                    if (exprList[0].exprSubType == OpTypes.LIKE) {
                        indexEndCond[0] = indexEndCondition =
                            exprList[0].nodes[2];
                    }

                    opTypeEnd     = OpTypes.MAX;
                    opTypesEnd[0] = OpTypes.MAX;
                    break;

                case OpTypes.SMALLER :
                case OpTypes.SMALLER_EQUAL : {
                    Expression e = exprList[0].getLeftNode();

                    e = new ExpressionLogical(OpTypes.IS_NULL, e);
                    e               = new ExpressionLogical(OpTypes.NOT, e);
                    indexCond[0]    = e;
                    indexEndCond[0] = indexEndCondition = exprList[0];
                    opTypeEnd       = opType;
                    opTypesEnd[0]   = opType;
                    opType          = OpTypes.NOT;
                    opTypes[0]      = OpTypes.NOT;

                    break;
                }
                case OpTypes.IS_NULL :
                case OpTypes.EQUAL : {
                    indexCond = exprList;

                    for (int i = 0; i < colCount; i++) {
                        Expression e = exprList[i];

                        indexEndCond[i] = e;
                        indexEndCondition =
                            ExpressionLogical.andExpressions(indexEndCondition,
                                                             e);
                        opType     = e.opType;
                        opTypes[0] = e.opType;
                    }

                    opTypeEnd = opType;

                    break;
                }
                default :
                    Error.runtimeError(ErrorCode.U_S0500, "RangeVariable");
            }

            indexedColumnCount = colCount;
            hasIndex           = true;
        }

        public void reverseIndexCondition() {

            if (opType == OpTypes.EQUAL || opType == OpTypes.IS_NULL) {
                return;
            }

            indexEndCondition = null;

            for (int i = 0; i < indexedColumnCount; i++) {
                Expression e = indexCond[i];

                indexCond[i]    = indexEndCond[i];
                indexEndCond[i] = e;
                indexEndCondition =
                    ExpressionLogical.andExpressions(indexEndCondition, e);
            }

            opType   = opTypeEnd;
            reversed = true;
        }

        String describe(Session session, int blanks) {

            StringBuffer sb = new StringBuffer();
            String       b  = ValuePool.spaceString.substring(0, blanks);

            sb.append("index=").append(rangeIndex.getName().name).append("\n");

            if (hasIndexCondition()) {
                if (indexedColumnCount > 0) {
                    sb.append(b).append("start conditions=[");

                    for (int j = 0; j < indexedColumnCount; j++) {
                        if (indexCond != null && indexCond[j] != null) {
                            sb.append(indexCond[j].describe(session, blanks));
                        }
                    }

                    sb.append("]\n");
                }

                if (indexEndCondition != null) {
                    String temp = indexEndCondition.describe(session, blanks);

                    sb.append(b).append("end condition=[").append(temp).append(
                        "]\n");
                }
            }

            if (nonIndexCondition != null) {
                String temp = nonIndexCondition.describe(session, blanks);

                sb.append(b).append("other condition=[").append(temp).append(
                    "]\n");
            }

            return sb.toString();
        }
    }
}
