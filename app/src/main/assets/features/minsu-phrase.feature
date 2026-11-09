Feature: 民宿词库功能验收

  作为输入法用户
  我希望输入民宿相关拼音时，相关词汇能优先显示在候选词首位
  以便快速选择和输入

  Scenario: 民宿核心词汇显示
    Given 用户输入 "min su"
    When 查看候选词列表
    Then 候选词首位必须显示 "民宿"（权重9999）
    And 候选词第二位必须显示 "民宿家"（权重9999）
    And 候选词第三位必须显示 "民宿预订"（权重9999）

  Scenario: 民宿设施词汇显示
    Given 用户输入 "yong chi"
    When 查看候选词列表
    Then 候选词首位必须显示 "泳池"（权重9999）
    And 候选词第二位必须显示 "游泳池"（权重3715）

  Scenario: 泡池词汇显示
    Given 用户输入 "pao chi"
    When 查看候选词列表
    Then 候选词首位必须显示 "泡池"（权重9999）

  Scenario: 庭院词汇显示
    Given 用户输入 "ting yuan"
    When 查看候选词列表
    Then 候选词首位必须显示 "庭院"（权重9999）

  Scenario: 露台词汇显示
    Given 用户输入 "lu tai"
    When 查看候选词列表
    Then 候选词首位必须显示 "露台"（权重9999）