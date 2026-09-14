package ph.epr.api.identitytenancy;

import java.util.List;

public record PracticeAdministrationPage(
	List<PracticeAdministrationView> items,
	long totalElements,
	int page,
	int size
) {
	public PracticeAdministrationPage {
		items = List.copyOf(items);
	}

	public int totalPages() {
		return totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
	}
}
