/* Copyright (c) 2001-2007, The HSQL Development Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of the HSQL Development Group nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL HSQL DEVELOPMENT GROUP, HSQLDB.ORG,
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package org.hsqldb;

import org.hsqldb.HsqlNameManager.HsqlName;
import org.hsqldb.lib.HashSet;
import org.hsqldb.lib.OrderedHashSet;

// fredt@users 20020420 - patch523880 by leptipre@users - VIEW support - modified
// fredt@users 20031227 - remimplementated as compiled query

/**
 * Represents an SQL VIEW based on a SELECT statement.
 *
 * @author leptipre@users
 * @author fredt@users
 * @version 1.9.0
 * @since 1.7.0
 */
public class View extends Table {

    Select         viewSelect;
    SubQuery       viewSubQuery;
    private String statement;
    HsqlName[]     colList;

    /** schema at the time of compilation */
    HsqlName compileTimeSchema;

    /**
     * List of subqueries in this view in order of materialization. Last
     * element is the view itself.
     */
    SubQuery[] viewSubqueries;

    /**
     * Names of SCHEMA objects referenced in VIEW
     */
    OrderedHashSet schemaObjectNames;

    /**
     * Constructor.
     * @param Session
     * @param db database
     * @param name HsqlName of the view
     * @param definition SELECT statement of the view
     * @param columns array of HsqlName column names
     * @throws HsqlException
     */
    View(Session session, Database db, HsqlName name, String definition,
            HsqlName[] columns) throws HsqlException {

        super(db, name, VIEW);

        isReadOnly        = true;
        colList           = columns;
        statement         = definition;
        compileTimeSchema = session.getSchemaHsqlName(null);

        compile(session);
    }

    public OrderedHashSet getReferences() {
        return schemaObjectNames;
    }

    /**
     * Compiles the SELECT statement and sets up the columns.
     */
    public void compile(Session session) throws HsqlException {

        if (!database.schemaManager.schemaExists(compileTimeSchema.name)) {
            compileTimeSchema = session.getSchemaHsqlName(null);
        }

        session.setSchema(compileTimeSchema.name);

        Parser p = new Parser(session, new Tokenizer(statement));

        p.read();

        viewSubQuery = p.readSubquery(0, this, true, Expression.VIEW);

        viewSubqueries = p.compileContext.getSubqueries();

        for (int i = 0; i < viewSubqueries.length; i++) {
            if (viewSubqueries[i].parentView == null) {
                viewSubqueries[i].parentView = this;
            }
        }


        viewSelect        = viewSubQuery.select;
        schemaObjectNames = p.compileContext.getSchemaObjectNames();

        if (super.getColumnCount() == 0) {
            columnList  = viewSubQuery.table.columnList;
            columnCount = viewSubQuery.table.getColumnCount();
        } else {
            viewSubQuery.table.columnList = columnList;
        }
    }

    /**
     * Returns the SELECT statement for the view.
     */
    public String getStatement() {
        return statement;
    }

    /**
     * Overridden to disable SET TABLE READONLY DDL for View objects.
     */
    public void setDataReadOnly(boolean value) throws HsqlException {
        throw Trace.error(Trace.NOT_A_TABLE);
    }

    public void collectAllBaseColumnExpressions(HashSet set) {

        Expression.collectAllExpressions(
            set, viewSubqueries[viewSubqueries.length - 1].select,
            Expression.COLUMN);
    }

    public void collectAllFunctionExpressions(HashSet set) {

        Expression.collectAllExpressions(
            set, viewSubqueries[viewSubqueries.length - 1].select,
            Expression.FUNCTION);
    }
}
