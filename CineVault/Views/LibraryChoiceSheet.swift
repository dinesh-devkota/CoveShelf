import SwiftUI

/// A single choice takes effect immediately. A picker with an All row stages multiple choices until Apply.
struct LibraryChoiceSheet<Value: Hashable>: View {
    private enum Row: Hashable { case all, option(Value), apply }

    let title: String
    let options: [Value]
    let all: String?
    let label: (Value) -> String
    let apply: (Set<Value>) -> Void
    @State private var draft: Set<Value>
    @State private var lastChoice: Row?
    @FocusState private var focus: Row?
    @Environment(\.dismiss) private var dismiss

    init(title: String, options: [Value], selection: Set<Value>, all: String? = nil,
         label: @escaping (Value) -> String, apply: @escaping (Set<Value>) -> Void) {
        self.title = title
        self.options = options
        self.all = all
        self.label = label
        self.apply = apply
        _draft = State(initialValue: selection)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            HStack(spacing: 24) {
                Text(title).font(.largeTitle.bold())
                Spacer()
                Button("Cancel") { dismiss() }
                if all != nil {
                    Button("Apply") {
                        apply(draft)
                        dismiss()
                    }
                    .buttonStyle(.borderedProminent)
                    .focused($focus, equals: .apply)
                    .onMoveCommand { direction in
                        if direction == .left { focus = lastChoice ?? .all }
                    }
                }
            }
            ScrollViewReader { scroll in
                ScrollView {
                    VStack(spacing: 12) {
                        if let all {
                            choiceRow(.all, text: all, checked: draft.isEmpty) { draft.removeAll() }
                        }
                        ForEach(options, id: \.self) { option in
                            choiceRow(.option(option), text: label(option), checked: draft.contains(option)) {
                                if all == nil {
                                    apply([option])
                                    dismiss()
                                } else if !draft.insert(option).inserted {
                                    draft.remove(option)
                                }
                            }
                        }
                    }
                    .padding(18)
                }
                .task {
                    let target = options.first(where: draft.contains).map(Row.option) ?? .all
                    scroll.scrollTo(target, anchor: .center)
                    await Task.yield()
                    if !Task.isCancelled { focus = target }
                }
            }
        }
        .padding(56)
        .frame(maxWidth: 1150)
        .onChange(of: focus) { _, current in
            if let current, current != .apply { lastChoice = current }
        }
        .onExitCommand { dismiss() }
    }

    @ViewBuilder
    private func choiceRow(_ row: Row, text: String, checked: Bool, action: @escaping () -> Void) -> some View {
        let button = Button(action: action) {
            HStack {
                Text(text)
                Spacer()
                if checked { Image(systemName: "checkmark").accessibilityHidden(true) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .focused($focus, equals: row)
        .accessibilityAddTraits(checked ? .isSelected : [])
        .id(row)

        if all != nil {
            button.onMoveCommand { direction in
                if direction == .right {
                    lastChoice = row
                    focus = .apply
                }
            }
        } else {
            button
        }
    }
}
