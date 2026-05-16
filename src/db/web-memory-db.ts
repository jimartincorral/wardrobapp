/**
 * Simple in-memory SQL-compatible adapter for web testing.
 *
 * Parses just enough SQL to handle the queries our app uses:
 * - CREATE TABLE IF NOT EXISTS
 * - CREATE INDEX IF NOT EXISTS
 * - INSERT INTO ... VALUES (?)
 * - SELECT ... FROM ... WHERE ... ORDER BY ... LIMIT
 * - UPDATE ... SET ... WHERE
 * - DELETE FROM ... WHERE
 * - PRAGMA (ignored)
 *
 * Data persists across page refreshes via localStorage (JSON).
 */

import type { DatabaseAdapter } from './client';

type Row = Record<string, any>;
type Table = Row[];

interface MemoryDb {
  tables: Record<string, Table>;
}

const STORAGE_KEY = 'wardrobapp_memdb';

function loadFromStorage(): MemoryDb {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return JSON.parse(raw);
  } catch {}
  return { tables: {} };
}

function saveToStorage(memDb: MemoryDb) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(memDb));
  } catch {}
}

// ── Simple expression evaluator for WHERE clauses ───────────
function matchesWhere(row: Row, whereClause: string, params: any[]): boolean {
  if (!whereClause.trim()) return true;

  let paramIndex = 0;
  let clause = whereClause;

  // Replace ? placeholders with values
  const resolved = clause.replace(/\?/g, () => {
    const val = params[paramIndex++];
    if (val === null || val === undefined) return 'NULL';
    if (typeof val === 'string') return `'${val.replace(/'/g, "''")}'`;
    return String(val);
  });

  return evalWhere(row, resolved);
}

function evalWhere(row: Row, clause: string): boolean {
  const trimmed = clause.trim();

  // Handle tautologies like "1=1" or "1 = 1" (always true)
  if (/^\d+\s*=\s*\d+$/.test(trimmed)) {
    const [l, r] = trimmed.split('=').map(s => s.trim());
    return l === r;
  }

  // Handle AND
  const andParts = splitTopLevel(trimmed, ' AND ');
  if (andParts.length > 1) {
    return andParts.every(p => evalWhere(row, p.trim()));
  }

  // Handle OR
  const orParts = splitTopLevel(trimmed, ' OR ');
  if (orParts.length > 1) {
    return orParts.some(p => evalWhere(row, p.trim()));
  }

  // Handle IS NULL / IS NOT NULL
  const isNullMatch = trimmed.match(/^(\w+)\s+IS\s+(NOT\s+)?NULL$/i);
  if (isNullMatch) {
    const val = row[isNullMatch[1]];
    const isNull = val === null || val === undefined;
    return isNullMatch[2] ? !isNull : isNull;
  }

  // Handle LIKE
  const likeMatch = trimmed.match(/^(\w+)\s+LIKE\s+'(.+)'$/i);
  if (likeMatch) {
    const val = String(row[likeMatch[1]] ?? '').toLowerCase();
    const pattern = likeMatch[2].toLowerCase().replace(/%/g, '.*').replace(/_/g, '.');
    return new RegExp(`^${pattern}$`).test(val);
  }

  // Handle comparisons: =, !=, <, >, <=, >=
  const cmpMatch = trimmed.match(/^(\w+)\s*(=|!=|<>|<=|>=|<|>)\s*(.+)$/i);
  if (cmpMatch) {
    const col = cmpMatch[1];
    const op = cmpMatch[2];
    const raw = cmpMatch[3].trim();
    const left = row[col];
    const right = parseValue(raw);

    switch (op) {
      case '=': return left == right;
      case '!=': case '<>': return left != right;
      case '<': return Number(left) < Number(right);
      case '>': return Number(left) > Number(right);
      case '<=': return Number(left) <= Number(right);
      case '>=': return Number(left) >= Number(right);
    }
  }

  return true;
}

function parseValue(raw: string): any {
  const trimmed = raw.trim();
  if (trimmed === 'NULL') return null;
  if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
    return trimmed.slice(1, -1).replace(/''/g, "'");
  }
  const num = Number(trimmed);
  return isNaN(num) ? trimmed : num;
}

function splitTopLevel(str: string, sep: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = '';
  const sepUpper = sep.toUpperCase();
  let i = 0;

  while (i < str.length) {
    if (str[i] === '(') depth++;
    else if (str[i] === ')') depth--;

    if (depth === 0 && str.slice(i).toUpperCase().startsWith(sepUpper)) {
      parts.push(current);
      current = '';
      i += sep.length;
      continue;
    }
    current += str[i];
    i++;
  }
  parts.push(current);
  return parts.length > 1 ? parts : [str];
}

// ── SQL parser ────────────────────────────────────────────────
function parseInsert(sql: string, params: any[], memDb: MemoryDb): void {
  const match = sql.match(
    /INSERT\s+(?:OR\s+REPLACE\s+)?INTO\s+(\w+)\s*\(([^)]+)\)\s*VALUES\s*\(([^)]+)\)/i
  );
  if (!match) return;

  const tableName = match[1];
  const columns = match[2].split(',').map(c => c.trim());
  const valuePlaceholders = match[3].split(',').map(v => v.trim());

  if (!memDb.tables[tableName]) memDb.tables[tableName] = [];

  let paramIdx = 0;
  const row: Row = {};
  for (let i = 0; i < columns.length; i++) {
    const placeholder = valuePlaceholders[i];
    if (placeholder === '?') {
      row[columns[i]] = params[paramIdx++] ?? null;
    } else {
      row[columns[i]] = parseValue(placeholder);
    }
  }
  memDb.tables[tableName].push(row);
}

function parseUpdate(sql: string, params: any[], memDb: MemoryDb): void {
  const match = sql.match(/UPDATE\s+(\w+)\s+SET\s+(.+?)\s+WHERE\s+(.+)$/is);
  if (!match) return;

  const tableName = match[1];
  const setClause = match[2];
  const whereClause = match[3];

  if (!memDb.tables[tableName]) return;

  // Parse SET clause
  let paramIdx = 0;
  const sets = setClause.split(/,(?![^']*')/).map(s => {
    const [col, ...valParts] = s.split('=');
    const val = valParts.join('=').trim();
    return { col: col.trim(), val };
  });

  // Count params needed for WHERE
  const setParams: any[] = [];
  for (const s of sets) {
    if (s.val === '?') setParams.push(params[paramIdx++]);
  }
  const whereParams = params.slice(paramIdx);

  memDb.tables[tableName] = memDb.tables[tableName].map(row => {
    if (!matchesWhere(row, whereClause, whereParams)) return row;
    const newRow = { ...row };
    let spIdx = 0;
    for (const s of sets) {
      newRow[s.col] = s.val === '?' ? setParams[spIdx++] : parseValue(s.val);
    }
    return newRow;
  });
}

function parseDelete(sql: string, params: any[], memDb: MemoryDb): void {
  const match = sql.match(/DELETE\s+FROM\s+(\w+)(?:\s+WHERE\s+(.+))?$/is);
  if (!match) return;

  const tableName = match[1];
  const whereClause = match[2] || '';

  if (!memDb.tables[tableName]) return;

  if (!whereClause.trim()) {
    memDb.tables[tableName] = [];
    return;
  }

  memDb.tables[tableName] = memDb.tables[tableName].filter(
    row => !matchesWhere(row, whereClause, params)
  );
}

function parseSelect(sql: string, params: any[], memDb: MemoryDb): Row[] {
  // Handle COALESCE in SELECT - simplified: just strip COALESCE wrapper
  const cleanSql = sql
    .replace(/COALESCE\(([^,]+),\s*0\)/gi, (_, col) => col.trim())
    .replace(/COALESCE\(([^,]+),\s*([^)]+)\)/gi, (_, col) => col.trim());

  // Parse: SELECT cols FROM table [JOIN...] [WHERE...] [GROUP BY...] [HAVING...] [ORDER BY...] [LIMIT...]
  const fromMatch = cleanSql.match(/FROM\s+(\w+)(.*)/is);
  if (!fromMatch) return [];

  const tableName = fromMatch[1];
  const rest = fromMatch[2];

  // Handle LEFT JOIN
  const joinMatch = rest.match(/LEFT\s+JOIN\s*\(([^)]+)\)\s+(\w+)\s+ON\s+(\w+)\.(\w+)\s*=\s*(\w+)\.(\w+)/i);
  let rows: Row[] = [...(memDb.tables[tableName] || [])];

  if (joinMatch) {
    // Execute subquery
    const subquery = joinMatch[1];
    const alias = joinMatch[2];
    const subRows = parseSelect(subquery, params, memDb);
    const subMap: Record<string, Row> = {};
    const joinCol = joinMatch[4];
    for (const sr of subRows) subMap[sr[joinCol]] = sr;

    const mainJoinCol = joinMatch[6];
    rows = rows.map(r => {
      const sub = subMap[r[mainJoinCol]] || {};
      const aliased: Row = {};
      for (const [k, v] of Object.entries(sub)) aliased[`${alias}.${k}`] = v;
      return { ...r, ...sub, ...aliased };
    });
  }

  // Parse WHERE
  const whereMatch = rest.match(/WHERE\s+(.+?)(?:\s+GROUP\s+BY|\s+HAVING|\s+ORDER\s+BY|\s+LIMIT|$)/is);
  const whereClause = whereMatch ? whereMatch[1].trim() : '';

  let paramOffset = 0;
  if (whereClause) {
    rows = rows.filter(row => matchesWhere(row, whereClause, params.slice(paramOffset)));
    paramOffset += (whereClause.match(/\?/g) || []).length;
  }

  // Parse GROUP BY
  const groupMatch = rest.match(/GROUP\s+BY\s+(\w+)/i);
  if (groupMatch) {
    const groupCol = groupMatch[1];
    const grouped: Record<string, Row[]> = {};
    for (const row of rows) {
      const key = String(row[groupCol] ?? 'NULL');
      if (!grouped[key]) grouped[key] = [];
      grouped[key].push(row);
    }

    // Aggregate: handle COUNT(*), SUM(), AVG(), MAX(), MIN() in SELECT
    const selectMatch = cleanSql.match(/SELECT\s+(.+?)\s+FROM/is);
    const selectCols = selectMatch ? selectMatch[1] : '*';

    rows = Object.entries(grouped).map(([key, groupRows]) => {
      const aggregated: Row = { ...groupRows[0] };

      // COUNT(*)
      aggregated['COUNT(*)'] = groupRows.length;
      const countAlias = selectCols.match(/COUNT\(\*\)\s+as\s+(\w+)/i);
      if (countAlias) aggregated[countAlias[1]] = groupRows.length;

      // COUNT(id) or COUNT(anything)
      const countCol = selectCols.match(/COUNT\((\w+)\)\s+as\s+(\w+)/i);
      if (countCol) aggregated[countCol[2]] = groupRows.filter(r => r[countCol[1]] != null).length;

      // SUM
      const sumMatch = selectCols.match(/SUM\((\w+)\)\s+as\s+(\w+)/i);
      if (sumMatch) {
        aggregated[sumMatch[2]] = groupRows.reduce((s, r) => s + (Number(r[sumMatch[1]]) || 0), 0);
      }

      // AVG
      const avgMatch = selectCols.match(/AVG\((\w+)\)\s+as\s+(\w+)/i);
      if (avgMatch) {
        const sum = groupRows.reduce((s, r) => s + (Number(r[avgMatch[1]]) || 0), 0);
        aggregated[avgMatch[2]] = groupRows.length ? sum / groupRows.length : 0;
      }

      // MAX
      const maxMatch = selectCols.match(/MAX\((\w+)\)\s+as\s+(\w+)/i);
      if (maxMatch) {
        aggregated[maxMatch[2]] = Math.max(...groupRows.map(r => r[maxMatch[1]] ?? -Infinity));
      }

      // count column
      if (selectCols.includes('count')) {
        aggregated['count'] = groupRows.length;
      }

      return aggregated;
    });

    // HAVING
    const havingMatch = rest.match(/HAVING\s+(.+?)(?:\s+ORDER\s+BY|\s+LIMIT|$)/is);
    if (havingMatch) {
      rows = rows.filter(row => matchesWhere(row, havingMatch[1].trim(), []));
    }
  }

  // Handle aggregate functions WITHOUT GROUP BY (e.g., SELECT COUNT(*) as count FROM ...)
  if (!groupMatch) {
    const selectMatch = cleanSql.match(/SELECT\s+(.+?)\s+FROM/is);
    const selectCols = selectMatch ? selectMatch[1] : '*';

    if (/\b(COUNT|SUM|AVG|MAX|MIN)\s*\(/i.test(selectCols)) {
      const aggregated: Row = {};

      // COUNT(*)
      const countStarAlias = selectCols.match(/COUNT\(\*\)\s+as\s+(\w+)/i);
      if (countStarAlias) {
        aggregated[countStarAlias[1]] = rows.length;
        aggregated['COUNT(*)'] = rows.length;
      } else if (/COUNT\(\*\)/i.test(selectCols)) {
        aggregated['COUNT(*)'] = rows.length;
      }

      // COUNT(col)
      const countColMatch = selectCols.match(/COUNT\((\w+)\)\s+as\s+(\w+)/i);
      if (countColMatch) {
        aggregated[countColMatch[2]] = rows.filter(r => r[countColMatch[1]] != null).length;
      }

      // SUM
      const sumMatch = selectCols.match(/SUM\((\w+)\)\s+as\s+(\w+)/i);
      if (sumMatch) {
        aggregated[sumMatch[2]] = rows.reduce((s, r) => s + (Number(r[sumMatch[1]]) || 0), 0);
      }

      // AVG
      const avgMatch = selectCols.match(/AVG\((\w+)\)\s+as\s+(\w+)/i);
      if (avgMatch) {
        const sum = rows.reduce((s, r) => s + (Number(r[avgMatch[1]]) || 0), 0);
        aggregated[avgMatch[2]] = rows.length ? sum / rows.length : 0;
      }

      // MAX
      const maxMatch = selectCols.match(/MAX\((\w+)\)\s+as\s+(\w+)/i);
      if (maxMatch) {
        aggregated[maxMatch[2]] = rows.length
          ? Math.max(...rows.map(r => r[maxMatch[1]] ?? -Infinity))
          : null;
      }

      // MIN
      const minMatch = selectCols.match(/MIN\((\w+)\)\s+as\s+(\w+)/i);
      if (minMatch) {
        aggregated[minMatch[2]] = rows.length
          ? Math.min(...rows.map(r => r[minMatch[1]] ?? Infinity))
          : null;
      }

      rows = [aggregated];
    }
  }

  // Parse ORDER BY
  const orderMatch = rest.match(/ORDER\s+BY\s+(.+?)(?:\s+LIMIT|$)/is);
  if (orderMatch) {
    const orderParts = orderMatch[1].split(',').map(p => {
      const parts = p.trim().split(/\s+/);
      return { col: parts[0], desc: parts[1]?.toUpperCase() === 'DESC' };
    });
    rows.sort((a, b) => {
      for (const { col, desc } of orderParts) {
        const av = a[col], bv = b[col];
        if (av == bv) continue;
        const cmp = av == null ? -1 : bv == null ? 1 : av < bv ? -1 : 1;
        return desc ? -cmp : cmp;
      }
      return 0;
    });
  }

  // Parse LIMIT
  const limitMatch = rest.match(/LIMIT\s+(\d+)/i);
  if (limitMatch) {
    rows = rows.slice(0, parseInt(limitMatch[1]));
  }

  return rows;
}

// ── Public factory ────────────────────────────────────────────
export function createMemoryAdapter(): DatabaseAdapter {
  const memDb = loadFromStorage();
  let persistTimer: ReturnType<typeof setTimeout> | null = null;

  const persist = () => saveToStorage(memDb);
  const schedulePersist = () => {
    if (persistTimer) clearTimeout(persistTimer);
    persistTimer = setTimeout(persist, 500);
  };

  const exec = (sql: string, params: any[] = []) => {
    const upper = sql.trim().toUpperCase();
    if (upper.startsWith('CREATE') || upper.startsWith('PRAGMA')) return;
    if (upper.startsWith('INSERT')) { parseInsert(sql, params, memDb); schedulePersist(); }
    else if (upper.startsWith('UPDATE')) { parseUpdate(sql, params, memDb); schedulePersist(); }
    else if (upper.startsWith('DELETE')) { parseDelete(sql, params, memDb); schedulePersist(); }
  };

  return {
    execAsync: async (sql: string) => {
      // Handle multiple statements separated by semicolons
      const stmts = sql.split(';').map(s => s.trim()).filter(Boolean);
      for (const stmt of stmts) exec(stmt, []);
    },

    runAsync: async (sql: string, ...params: any[]) => {
      exec(sql, params);
    },

    getFirstAsync: async <T,>(sql: string, ...params: any[]): Promise<T | null> => {
      const rows = parseSelect(sql, params, memDb);
      return rows.length > 0 ? (rows[0] as T) : null;
    },

    getAllAsync: async <T,>(sql: string, ...params: any[]): Promise<T[]> => {
      return parseSelect(sql, params, memDb) as T[];
    },

    closeAsync: async () => {
      persist();
    },
  };
}
