/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package docking.widgets.table.threaded;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import docking.widgets.filter.*;
import docking.widgets.table.*;
import ghidra.docking.settings.Settings;
import ghidra.docking.spy.SpyEventRecorder;
import ghidra.framework.plugintool.ServiceProvider;
import ghidra.util.task.TaskMonitor;

public class ThreadedTableFilterTest extends AbstractThreadedTableTest {

	private SpyEventRecorder recorder = new SpyEventRecorder(getClass().getSimpleName());
	private SpyTaskMonitor monitor = new SpyTaskMonitor();
	private SpyTextFilter<Long> spyFilter;
	private ThreadedTableModelListener spyLoadListener = new SpyTableModelListener();

	@Override
	protected TestDataKeyModel createTestModel() {
		AtomicReference<TestDataKeyModel> ref = new AtomicReference<>();

		// Note: from the test model, the data looks like this:
		//  "one", "two", "THREE", "Four", "FiVe", "sIx", "SeVEn", "EighT", "NINE", 
		//  "ten", "ten", "ten" 
		runSwing(() -> ref.set(new TestDataKeyModel(monitor, false) {
			@Override
			void setDefaultTaskMonitor(TaskMonitor monitor) {
				// No! some of our tests use a spy monitor.  If you ever find that you
				// need the standard monitors to get wired, then wrap the monitor being
				// passed-in here by the spy and let it delegate whilst recording messages
			}

			@Override
			public void setIncrementalTaskMonitor(TaskMonitor monitor) {
				// no! some of our tests use a spy monitor
			}

			@Override
			protected TableColumnDescriptor<Long> createTableColumnDescriptor() {
				TableColumnDescriptor<Long> descriptor = super.createTableColumnDescriptor();

				// add our own custom column to test filtering
				descriptor.addVisibleColumn(new RawRowValueTableColumn());

				return descriptor;
			}
		}));
		return ref.get();
	}

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();

		// Restore this JVM property, as some tests change it
		System.setProperty(RowObjectFilterModel.SUB_FILTERING_DISABLED_PROPERTY,
			Boolean.FALSE.toString());

		waitForTableModel(model);

		// must run in Swing so that we do not mutate listeners while events are broadcasting
		runSwing(() -> model.addThreadedTableModelListener(spyLoadListener));
	}

	@Override
	protected void testFailed(Throwable e) {
		recorder.record("Test - testFailed()");
		// let our event recorder get all the events that were pending in the client code
		waitForNotBusy();
		recorder.dumpEvents();
	}

	@Test
	public void testRefilterHappensAfterAddItem_ItemAddedPassesFilter() throws Exception {

		int newRowIndex = model.getRowCount() + 1;

		filterOnRawColumnValue(newRowIndex);
		resetSpies();

		assertTableDoesNotContainValue(newRowIndex);

		addItemToModel(newRowIndex);

		assertDidFilter();
		assertTableContainsValue(newRowIndex);
	}

	@Test
	public void testRefilterHappensAfterRemoveAddItem_ItemAddedPassesFilter() throws Exception {

		int newRowIndex = model.getRowCount() + 1;

		filterOnRawColumnValue(newRowIndex);
		resetSpies();

		assertTableDoesNotContainValue(newRowIndex);

		addItemToModel(newRowIndex);
		assertDidFilter();
		assertTableContainsValue(newRowIndex);

		removeItemFromModel(newRowIndex);
		assertDidFilter();
		assertTableDoesNotContainValue(newRowIndex);

		addItemToModel(newRowIndex);
		assertDidFilter();
		assertTableContainsValue(newRowIndex);
	}

	@Test
	public void testRefilterHappensAfterAdd_ItemAddedFailsFilter() throws Exception {

		int newRowIndex = model.getRowCount() + 1;

		long nonMatchingFilter = 1;
		filterOnRawColumnValue(nonMatchingFilter);
		resetSpies();

		assertTableDoesNotContainValue(newRowIndex);

		addItemToModel(newRowIndex);

		assertDidFilter();
		assertTableDoesNotContainValue(newRowIndex);
	}

	@Test
	public void testSubFilter() throws Exception {

		//
		// Our filters are smart enough to filter using the previous data when the new filter
		// is a subset of the previous filter.  Test that here.
		//

		startsWithFilter("t");
		assertFilteredEntireModel();
		assertRowCount(4); // matching values: two, ten, ten, ten

		// sub-filter
		startsWithFilter("te");
		assertNumberOfItemsPassedThroughFilter(4);
		assertRowCount(3); // matching values: ten, ten, ten

		// sub-filter again
		startsWithFilter("ten");
		assertNumberOfItemsPassedThroughFilter(3);
		assertRowCount(3); // matching values: ten, ten, ten

		// not a sub-filter
		startsWithFilter("F");
		assertFilteredEntireModel();
		assertRowCount(2); // matching values: Four, FiVe
	}

	@Test
	public void testSubFilter_RoundTrip_StartsWithFilter() throws Exception {

		//
		// Test that sub-filters are used for each successive addition.  Then test that the 
		// previous filtered data is used when deleting characters.
		//

		startsWithFilter("t");
		assertFilteredEntireModel();
		assertRowCount(4); // matching values: two, ten, ten, ten

		// sub-filter
		startsWithFilter("te");
		assertNumberOfItemsPassedThroughFilter(4);
		assertRowCount(3); // matching values: ten, ten, ten

		// sub-filter again
		startsWithFilter("ten");
		assertNumberOfItemsPassedThroughFilter(3);
		assertRowCount(3); // matching values: ten, ten, ten

		// go backwards
		startsWithFilter("te");
		assertNumberOfItemsPassedThroughFilter(3);
		assertRowCount(3); // matching values: two, ten, ten, ten

		startsWithFilter("t");
		assertNumberOfItemsPassedThroughFilter(4);
		assertRowCount(4); // matching values: two, ten, ten, ten
	}

	@Test
	public void testSubFilter_RoundTrip_RegexFilter() throws Exception {

		//
		// Test that sub-filters are used for each successive addition.  Then test that the 
		// previous filtered data is used when deleting characters.
		//

		regexFilter("^t");
		assertFilteredEntireModel();
		assertRowCount(4); // matching values: two, ten, ten, ten

		// sub-filter
		regexFilter("^te");
		assertNumberOfItemsPassedThroughFilter(4);
		assertRowCount(3); // matching values: ten, ten, ten

		// sub-filter again
		regexFilter("^ten");
		assertNumberOfItemsPassedThroughFilter(3);
		assertRowCount(3); // matching values: ten, ten, ten

		// go backwards
		regexFilter("^te");
		assertNumberOfItemsPassedThroughFilter(3);
		assertRowCount(3); // matching values: two, ten, ten, ten

		regexFilter("^t");
		assertNumberOfItemsPassedThroughFilter(4);
		assertRowCount(4); // matching values: two, ten, ten, ten
	}

	@Test
	public void testSubFilter_DeleteMultipleCharacters() throws Exception {

		startsWithFilter("t");
		assertFilteredEntireModel();
		assertRowCount(4); // matching values: two, ten, ten, ten

		// sub-filter
		startsWithFilter("te");
		assertNumberOfItemsPassedThroughFilter(4);
		assertRowCount(3); // matching values: ten, ten, ten

		// sub-filter again
		startsWithFilter("ten");
		assertNumberOfItemsPassedThroughFilter(3);
		assertRowCount(3); // matching values: ten, ten, ten

		// jump from 'ten' to 't'--should still used the filtered data for 't'
		startsWithFilter("t");
		assertNumberOfItemsPassedThroughFilter(4);
		assertRowCount(4); // matching values: two, ten, ten, ten
	}

	@Test
	public void testRefilterHappens_ChangeFilterTypeNotText() throws Exception {

		//  "one", "two", "THREE", "Four", "FiVe", "sIx", "SeVEn", "EighT", "NINE", 
		//  "ten", "ten", "ten"

		startsWithFilter("o");
		assertFilteredEntireModel();
		assertRowCount(1); // matching values: one

		containsFilter("o");
		assertFilteredEntireModel();
		assertRowCount(3); // matching values: one, two, Four
	}

	@Test
	public void testRefilterHappens_ChangeFilterOptionNotText() throws Exception {

		//  "one", "two", "THREE", "Four", "FiVe", "sIx", "SeVEn", "EighT", "NINE", 
		//  "ten", "ten", "ten"

		startsWithFilter("t");
		assertFilteredEntireModel();
		assertRowCount(4); // matching values: two, ten, ten, ten

		startsWithFilter_CaseInsensitive("t");
		assertFilteredEntireModel();
		assertRowCount(5); // matching values: two, THREE, ten, ten, ten
	}

	@Test
	public void testDisableSubFilter() throws Exception {

		// Make sure we can disable the sub-filtering mechanism
		System.setProperty(RowObjectFilterModel.SUB_FILTERING_DISABLED_PROPERTY,
			Boolean.TRUE.toString());

		startsWithFilter("t");
		assertFilteredEntireModel();

		// sub-filter
		startsWithFilter("te");
		assertFilteredEntireModel();

		// sub-filter again
		startsWithFilter("ten");
		assertFilteredEntireModel();

		startsWithFilter("t");
		assertFilteredEntireModel();
	}

	@Test
	public void testCombinedTableFilter_TwoFilters_FirstFilterStandard_SecondFilterEmpty() {

		//
		// Test that a combined filter will properly support sub-filtering.   In this case, 
		// combine a standard filter as the initial filter, with a custom filter as the second
		// filter.   This custom filter allows us to control when we return true for 
		// 'isSubFilterOf'
		//

		TableFilter<Long> customFilter = new EmptyCustomFilter();
		createCombinedStartsWithFilter("t", customFilter);
		assertFilteredEntireModel();
		assertRowCount(4); // matching values: two, ten, ten, ten

		// sub-filter; the custom filter is the empty filter, which reports it can be a 
		// child/sub-filter of any other filter.  This means it should allow the primary filter
		// to work as normal.
		createCombinedStartsWithFilter("te", customFilter);
		assertNumberOfItemsPassedThroughFilter(4);
		assertRowCount(3); // matching values: ten, ten, ten

		createCombinedStartsWithFilter("t", customFilter);
		assertNumberOfItemsPassedThroughFilter(4);
		assertRowCount(4); // matching values: two, ten, ten, ten
	}

	@Test
	public void testCombinedTableFilter_TwoFilters_FirstFilterStandard_SecondFilterNonEmpty() {

		//
		// Test that a combined filter will properly support sub-filtering.   In this case, 
		// combine a standard filter as the initial filter, with a custom filter as the second
		// filter.   This custom filter allows us to control when we return true for 
		// 'isSubFilterOf'
		//

		TableFilter<Long> customFilter = new StringColumnContainsCustomFilter("t");
		createCombinedStartsWithFilter("t", customFilter);
		assertFilteredEntireModel();
		assertRowCount(4); // matching values (for both filters): two, ten, ten, ten

		// sub-filter should not work, as the custom filter always reports false for 'isSubFilterOf'
		createCombinedStartsWithFilter("te", customFilter);
		assertFilteredEntireModel();
		assertRowCount(3); // matching values: ten, ten, ten

		createCombinedStartsWithFilter("t", customFilter);
		assertFilteredEntireModel();
		assertRowCount(4); // matching values (for both filters): two, ten, ten, ten
	}

//==================================================================================================
// Private Methods
//==================================================================================================

	private void assertNumberOfItemsPassedThroughFilter(int expectedCount) {
		int numFiltered = spyFilter.getFilterCount();
		assertThat("Incorrect number of items filtered", numFiltered, is(expectedCount));
	}

	private void assertFilteredEntireModel() {
		int allCount = model.getUnfilteredCount();
		assertNumberOfItemsPassedThroughFilter(allCount);
	}

	private void assertTableContainsValue(long expected) {
		List<Long> modelValues = model.getModelData();
		assertTrue("Value not in the model--filtered out? - Expected " + expected + "; found " +
			modelValues, modelValues.contains(expected));
	}

	private void assertTableDoesNotContainValue(long expected) {
		List<Long> modelValues = model.getModelData();
		assertFalse("Value in the model--should not be there - Value " + expected + "; found " +
			modelValues, modelValues.contains(expected));
	}

	private void filterOnRawColumnValue(long filterValue) throws Exception {

		// the row objects are Long values that are 0-based one-up index values
		RowFilterTransformer<Long> transformer = value -> {
			List<String> result = Arrays.asList(Long.toString(value));
			return result;
		};

		FilterOptions options =
			new FilterOptions(TextFilterStrategy.MATCHES_EXACTLY, false, true, false);
		TextFilterFactory textFactory = options.getTextFilterFactory();
		TextFilter textFilter = textFactory.getTextFilter(Long.toString(filterValue));

		spyFilter = new SpyTextFilter<>(textFilter, transformer, recorder);

		runSwing(() -> model.setTableFilter(spyFilter));

		waitForNotBusy();
		waitForTableModel(model);
		waitForSwing();
	}

	private void regexFilter(String filterValue) {
		filterOnStringsColumnValue(filterValue, TextFilterStrategy.REGULAR_EXPRESSION);
	}

	private void startsWithFilter(String filterValue) {
		filterOnStringsColumnValue(filterValue, TextFilterStrategy.STARTS_WITH);
	}

	private void containsFilter(String filterValue) {
		filterOnStringsColumnValue(filterValue, TextFilterStrategy.CONTAINS);
	}

	private void filterOnStringsColumnValue(String filterValue, TextFilterStrategy filterStrategy) {

		// the row objects are Long values that are 0-based one-up index values
		DefaultRowFilterTransformer<Long> transformer =
			new DefaultRowFilterTransformer<>(model, table.getColumnModel());

		FilterOptions options = new FilterOptions(filterStrategy, false, true, false);
		TextFilterFactory textFactory = options.getTextFilterFactory();
		TextFilter textFilter = textFactory.getTextFilter(filterValue);

		spyFilter = new SpyTextFilter<>(textFilter, transformer, recorder);

		runSwing(() -> model.setTableFilter(spyFilter));

		waitForNotBusy();
		waitForTableModel(model);
		waitForSwing();
	}

	private void createCombinedStartsWithFilter(String filterValue,
			TableFilter<Long> secondFilter) {

		// the row objects are Long values that are 0-based one-up index values
		DefaultRowFilterTransformer<Long> transformer =
			new DefaultRowFilterTransformer<>(model, table.getColumnModel());

		TextFilterStrategy filterStrategy = TextFilterStrategy.STARTS_WITH;
		FilterOptions options = new FilterOptions(filterStrategy, false, true, false);
		TextFilterFactory textFactory = options.getTextFilterFactory();
		TextFilter textFilter = textFactory.getTextFilter(filterValue);

		spyFilter = new SpyTextFilter<>(textFilter, transformer, recorder);

		CombinedTableFilter<Long> combinedFilter =
			new CombinedTableFilter<Long>(spyFilter, secondFilter, null);

		runSwing(() -> model.setTableFilter(combinedFilter));

		waitForNotBusy();
		waitForTableModel(model);
		waitForSwing();
	}

	private void startsWithFilter_CaseInsensitive(String filterValue) {
		// the row objects are Long values that are 0-based one-up index values
		DefaultRowFilterTransformer<Long> transformer =
			new DefaultRowFilterTransformer<>(model, table.getColumnModel());

		FilterOptions options =
			new FilterOptions(TextFilterStrategy.STARTS_WITH, false, false, false);
		TextFilterFactory textFactory = options.getTextFilterFactory();
		TextFilter textFilter = textFactory.getTextFilter(filterValue);

		spyFilter = new SpyTextFilter<>(textFilter, transformer, recorder);

		runSwing(() -> model.setTableFilter(spyFilter));

		waitForNotBusy();
		waitForTableModel(model);
		waitForSwing();
	}

	private void resetSpies() {
		spyFilter.reset();
		monitor.clearMessages();
	}

	protected void assertDidNotFilter() {
		assertFalse("The table filtered data when it should not have", monitor.hasFilterMessage());
	}

	protected void assertDidFilter() {
		assertTrue("The table did not filter data when it should have", spyFilter.hasFiltered());
	}

//==================================================================================================
// Inner Classes
//==================================================================================================

	private class RawRowValueTableColumn extends AbstractDynamicTableColumnStub<Long, Long> {

		@Override
		public String getColumnName() {
			return "Raw Row Value";
		}

		@Override
		public Long getValue(Long rowObject, Settings settings, ServiceProvider provider)
				throws IllegalArgumentException {
			return rowObject;
		}
	}

	private class EmptyCustomFilter implements TableFilter<Long> {

		@Override
		public boolean acceptsRow(Long rowObject) {
			return true;
		}

		@Override
		public boolean isSubFilterOf(TableFilter<?> tableFilter) {
			// I pass everything, therefore anyone can be my parent
			return true;
		}
	}

	private class StringColumnContainsCustomFilter implements TableFilter<Long> {

		DefaultRowFilterTransformer<Long> transformer =
			new DefaultRowFilterTransformer<>(model, table.getColumnModel());
		private String filterText;

		StringColumnContainsCustomFilter(String filterText) {
			this.filterText = filterText;
		}

		@Override
		public boolean acceptsRow(Long rowObject) {

			List<String> strings = transformer.transform(rowObject);
			for (String s : strings) {
				if (s.contains(filterText)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean isSubFilterOf(TableFilter<?> tableFilter) {
			// for now we are too complicated to figure out if we are a sub-filter, so always 
			// return false
			return false;
		}

	}

	private class SpyTableModelListener implements ThreadedTableModelListener {

		@Override
		public void loadPending() {
			recorder.record("Swing - model load pending");
		}

		@Override
		public void loadingStarted() {
			recorder.record("Swing - model load started");
		}

		@Override
		public void loadingFinished(boolean wasCancelled) {
			if (wasCancelled) {
				recorder.record("Swing - model load cancelled");
			}
			else {
				recorder.record("Swing - model load finsished; size: " + model.getRowCount());
			}
		}

	}

}
