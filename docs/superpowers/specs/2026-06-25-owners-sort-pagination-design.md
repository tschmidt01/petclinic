# Owners List — Sorting & Pagination

**Date:** 2026-06-25
**Status:** Approved design

## Goal

Make the owners list sortable (ascending/descending by any scalar column) and
paginated (selectable 5/10/20 rows per page, with forward/backward navigation).
Builds on the existing case-insensitive multi-column owner search (commit 51886ea).

## Scope

- Backend: add server-side pagination + sorting to the owner search endpoint.
- Frontend: add clickable sort headers and pager controls to the existing
  Bootstrap 3 owners grid.

Out of scope: switching the grid to Angular Material; reactive/debounced search;
sorting by the Pets column; pg_trgm index work (tracked elsewhere).

## Backend

### Endpoint

`OwnerRestController.listOwners` changes from returning `List<OwnerDto>` to a
paginated response:

```
GET /api/owners?search=&page=0&size=10&sort=lastName,asc
→ 200 Page<OwnerDto>
```

Spring's `Page` JSON shape (`content`, `totalElements`, `totalPages`, `number`,
`size`, ...) — matches the existing, currently-unused `OwnerPage` frontend
interface.

- Add a `Pageable` parameter; Spring resolves `page`, `size`, `sort` from the
  query string.
- **Sort whitelist:** only `firstName`, `lastName`, `address`, `city`,
  `telephone` are permitted sort properties. Any other property → `400`
  (validate before hitting JPA so unknown/injected properties can't reach the
  query). A `firstName` ascending tiebreak is always appended to the requested
  sort.
- `search` keeps its current default (`""` = match all).

### Repository

Current query uses `LEFT JOIN o.pets` + `DISTINCT`. A collection join combined
with `Pageable` forces Hibernate to paginate in memory (HHH000104) and corrupts
counts. Rewrite to avoid the collection join:

```java
@Query("""
    SELECT o FROM Owner o WHERE
      LOWER(o.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.lastName)  LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.address)   LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.city)      LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.telephone) LIKE LOWER(CONCAT('%', :search, '%')) OR
      EXISTS (SELECT 1 FROM Pet p WHERE p.owner = o
              AND LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
Page<Owner> searchOwners(@Param("search") String search, Pageable pageable);
```

`EXISTS` keeps the row count = one per owner, so paging, sorting, and the derived
count query all run in SQL. Spring Data derives the count query automatically.
Pets are still serialized via the entity's `pets` collection through MapStruct
(unchanged).

### Tests (TDD)

- Page count / `totalElements` correct across multiple pages.
- Sort ascending and descending for each whitelisted column.
- Search still matches owner scalar fields **and** pet name (EXISTS path).
- Invalid sort property → `400`.
- Default request (no params) → page 0, size 10, sorted by `lastName` asc.

## Frontend (Bootstrap 3, custom controls)

### Service — `owner.service.ts`

Replace `getOwners()` / `searchOwners()` with a single paged call:

```ts
getOwners(search: string, page: number, size: number, sort: string)
  : Observable<OwnerPage>
```

`sort` formatted as `field,dir` (e.g. `lastName,asc`). Reuse the existing
`OwnerPage` interface.

### Component — `owner-list.component.ts`

State: `searchTerm`, `page = 0`, `size = 10`, `sortField = 'lastName'`,
`sortDir: 'asc' | 'desc' = 'asc'`, plus the loaded `OwnerPage`.

Single `load()` method issues the paged request; called after every state change.

- **Sort headers:** clicking a scalar `<th>` (Name→`lastName`, Address, City,
  Telephone) sets that field; clicking the active field toggles `asc`/`desc`.
  Changing sort resets `page` to 0. Active column shows an arrow (▲/▼). The Pets
  header is not clickable.
- **Pager:** Prev / Next buttons (disabled at first / last page), a
  "Page X of N" label, and a page-size `<select>` offering 5 / 10 / 20. Changing
  size resets `page` to 0.
- **Search:** stays click-triggered. Running a search resets `page` to 0 but
  keeps the current `size` and sort.

### Template — `owner-list.component.html`

Keep the existing Bootstrap 3 striped table and the nested pets `*ngFor`. Add
click handlers + arrow spans on sortable headers and a pager row below the table.

## Default Behavior

First load with no interaction: 10 rows per page, sorted by last name ascending
(firstName ascending tiebreak).
