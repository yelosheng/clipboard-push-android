#pragma once

#include <QMainWindow>
#include <QTextEdit>
#include <QPushButton>
#include <QLabel>

namespace ClipboardPush {

class MainWindow : public QMainWindow {
    Q_OBJECT

public:
    explicit MainWindow(QWidget* parent = nullptr);
    ~MainWindow() = default;

    void setStatus(const QString& text, const QString& color = "#666");

signals:
    void pushTextClicked(const QString& text);
    void settingsClicked();

protected:
    bool eventFilter(QObject* obj, QEvent* event) override;

private slots:
    void onPushClicked();

private:
    void setupUi();

    QTextEdit* m_textEdit = nullptr;
    QPushButton* m_pushButton = nullptr;
    QPushButton* m_settingsButton = nullptr;
    QLabel* m_statusLabel = nullptr;
};

} // namespace ClipboardPush
